#!/usr/bin/env python3
"""
Regenerates src/main/resources/discord_endpoints.json from Discord's official
OpenAPI 3.1 spec (https://github.com/discord/discord-api-spec).

Usage:
    python3 generate_discord_endpoints.py [--spec PATH_OR_URL] [--out OUTPUT_PATH]

By default it downloads the latest spec from GitHub (main branch) and writes
to src/main/resources/discord_endpoints.json relative to the repo root.

The output shape mirrors com.discordmcp.discord.EndpointModels.EndpointSpec
exactly (operationId, method, path, pathParams, queryParams, body, authType),
so RestToolRegistrar / EndpointRegistry require zero code changes when this
file is regenerated -- adding or removing Discord endpoints only ever touches
this JSON file.

Two OAuth2 token endpoints (/oauth2/token, /oauth2/token/revoke) are not
present in the official OpenAPI spec, so they are hand-appended at the end,
matching the historical hand-curated dataset.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

DEFAULT_SPEC_URL = "https://raw.githubusercontent.com/discord/discord-api-spec/main/specs/openapi.json"
HTTP_METHODS = ["get", "post", "put", "patch", "delete"]

HAND_ADDED_ENDPOINTS = [
    {
        "operationId": "oauth2_token_exchange",
        "method": "POST",
        "path": "/oauth2/token",
        "pathParams": [],
        "queryParams": [],
        "body": {
            "contentType": "application/x-www-form-urlencoded",
            "schemaName": "OAuth2TokenExchangeForm",
            "required": True,
            "hint": {
                "fields": ["grant_type", "code", "redirect_uri", "refresh_token", "scope", "client_id", "client_secret"],
                "required": ["grant_type"],
            },
        },
        "authType": "none",
    },
    {
        "operationId": "oauth2_token_revoke",
        "method": "POST",
        "path": "/oauth2/token/revoke",
        "pathParams": [],
        "queryParams": [],
        "body": {
            "contentType": "application/x-www-form-urlencoded",
            "schemaName": "OAuth2TokenRevokeForm",
            "required": True,
            "hint": {
                "fields": ["token", "token_type_hint", "client_id", "client_secret"],
                "required": ["token"],
            },
        },
        "authType": "none",
    },
]


def load_spec(spec_arg: str) -> dict:
    if spec_arg.startswith("http://") or spec_arg.startswith("https://"):
        with urllib.request.urlopen(spec_arg, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    return json.loads(Path(spec_arg).read_text(encoding="utf-8"))


def resolve_ref(schema: dict, schemas: dict) -> tuple[dict, str | None]:
    """Follow a single $ref hop. Returns (resolved_schema, ref_name_or_None)."""
    if isinstance(schema, dict) and "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        return schemas.get(name, {}), name
    return schema, None


def first_concrete_type(t) -> str | None:
    if isinstance(t, list):
        for candidate in t:
            if candidate != "null":
                return candidate
        return None
    return t


def json_type_of(t: str | None) -> str:
    return t if t in ("integer", "number", "boolean", "array", "object") else "string"


def enum_from_union(options: list) -> list | None:
    """Discord's spec encodes enums as oneOf/anyOf lists of {"const": ...}."""
    if options and all(isinstance(o, dict) and "const" in o for o in options):
        return [str(o["const"]) for o in options]
    return None


def type_info(schema: dict, schemas: dict, depth: int = 0) -> dict:
    """
    Returns a dict with jsonType / enum / pattern / items, matching
    ParamTypeInfo / ParamSpec shape (items only populated one level deep,
    matching the hand-curated dataset's granularity).
    """
    resolved, _ = resolve_ref(schema, schemas)

    union_key = "oneOf" if "oneOf" in resolved else ("anyOf" if "anyOf" in resolved else None)
    if union_key:
        options = resolved[union_key]
        enum = enum_from_union(options)
        if enum is not None:
            t = first_concrete_type(resolved.get("type")) or "string"
            return {"jsonType": json_type_of(t), "enum": enum, "pattern": resolved.get("pattern")}
        # Unions that aren't a simple enum (e.g. "string or array of snowflakes")
        # collapse to plain string, matching the historical hand-curated dataset's
        # convention of not exposing type-ambiguous unions as arrays.
        return {"jsonType": "string", "enum": None, "pattern": None}

    t = first_concrete_type(resolved.get("type"))
    if t == "array":
        items_schema = resolved.get("items", {})
        item_info = type_info(items_schema, schemas, depth + 1) if depth == 0 else {"jsonType": "string", "enum": None, "pattern": None}
        return {
            "jsonType": "array",
            "enum": None,
            "pattern": None,
            "items": {"jsonType": item_info["jsonType"], "enum": item_info.get("enum"), "pattern": item_info.get("pattern")},
        }

    enum = resolved.get("enum")
    if enum is not None:
        enum = [str(x) for x in enum]
    return {"jsonType": json_type_of(t), "enum": enum, "pattern": resolved.get("pattern")}


def build_param_spec(param: dict, schemas: dict) -> dict:
    info = type_info(param.get("schema", {}), schemas)
    spec = {"name": param["name"], "required": bool(param.get("required", False)), "jsonType": info["jsonType"]}
    if info.get("enum"):
        spec["enum"] = info["enum"]
    if info.get("pattern"):
        spec["pattern"] = info["pattern"]
    if info.get("items"):
        spec["items"] = {k: v for k, v in info["items"].items() if v is not None}
    return spec


def collect_params(path_item: dict, op_item: dict, schemas: dict) -> tuple[list, list]:
    by_name: dict[str, dict] = {}
    for p in path_item.get("parameters", []):
        by_name[(p.get("in"), p["name"])] = p
    for p in op_item.get("parameters", []):
        by_name[(p.get("in"), p["name"])] = p  # operation-level overrides path-level

    path_params = [build_param_spec(p, schemas) for (loc, _), p in by_name.items() if loc == "path"]
    query_params = [build_param_spec(p, schemas) for (loc, _), p in by_name.items() if loc == "query"]
    return path_params, query_params


# multipart/form-data is preferred over application/json when an operation offers both: it's a
# strict superset (the JSON body still goes through as the "payload_json" part -- see
# DiscordHttpClient.execute), and picking application/json here silently drops the ability to send
# the 'files' argument for endpoints like create_message / execute_webhook / update_webhook_message
# that support attachments only via multipart.
CONTENT_TYPE_PREFERENCE = ["multipart/form-data", "application/json", "application/x-www-form-urlencoded"]


def extract_ref_name(schema: dict) -> str | None:
    """
    Find a named schema for field hints, following a single $ref directly or
    inside an allOf/anyOf/oneOf. Discord's multipart/form-data request bodies
    are typically encoded either as
    `allOf: [{$ref: FooRequest}, {type: object, properties: {files[0]: ...}}]`
    (e.g. create_message, execute_webhook's sibling shape) or as a union of
    several possible request shapes via anyOf/oneOf (e.g. create_thread,
    create_interaction_response) -- a plain "$ref" in schema check misses both,
    which is what previously caused multipart endpoints to lose their
    field-hint descriptions once CONTENT_TYPE_PREFERENCE started choosing
    multipart/form-data over application/json. For anyOf/oneOf unions this
    just picks the first branch as a representative hint -- it's not
    exhaustive, but it beats no hint at all.
    """
    if not isinstance(schema, dict):
        return None
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    for key in ("allOf", "anyOf", "oneOf"):
        for sub in schema.get(key, []):
            name = extract_ref_name(sub)
            if name:
                return name
    return None


def build_body(op_item: dict, schemas: dict) -> dict | None:
    rb = op_item.get("requestBody")
    if not rb:
        return None
    content = rb.get("content", {})
    if not content:
        return None

    chosen_ct = next((ct for ct in CONTENT_TYPE_PREFERENCE if ct in content), None)
    if chosen_ct is None:
        chosen_ct = next(iter(content.keys()))
    chosen_schema = content[chosen_ct].get("schema", {}) or {}

    schema_name = None
    hint = None
    ref_name = extract_ref_name(chosen_schema)
    if ref_name:
        schema_name = ref_name
        target = schemas.get(schema_name, {})
        props = target.get("properties")
        if props is not None:
            hint = {"fields": list(props.keys()), "required": target.get("required", [])}

    return {
        "contentType": chosen_ct,
        "schemaName": schema_name,
        "required": bool(rb.get("required", False)),
        "hint": hint,
    }


def build_endpoints(spec: dict) -> list[dict]:
    schemas = spec.get("components", {}).get("schemas", {})
    paths = spec.get("paths", {})

    endpoints = []
    for path, path_item in paths.items():
        for method in HTTP_METHODS:
            op = path_item.get(method)
            if op is None:
                continue
            operation_id = op.get("operationId")
            if not operation_id:
                continue
            path_params, query_params = collect_params(path_item, op, schemas)
            endpoints.append(
                {
                    "operationId": operation_id,
                    "method": method.upper(),
                    "path": path,
                    "pathParams": path_params,
                    "queryParams": query_params,
                    "body": build_body(op, schemas),
                    "authType": "bot",
                },
            )

    endpoints.extend(HAND_ADDED_ENDPOINTS)
    return endpoints


def to_compact_json_lines(endpoints: list[dict]) -> str:
    lines = [json.dumps(e, ensure_ascii=False, separators=(",", ":")) for e in endpoints]
    return "[\n" + ",\n".join(lines) + "\n]\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", default=DEFAULT_SPEC_URL, help="URL or local path to openapi.json")
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parents[1] / "src/main/resources/discord_endpoints.json"),
        help="Output path for discord_endpoints.json",
    )
    args = parser.parse_args()

    spec = load_spec(args.spec)
    endpoints = build_endpoints(spec)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(to_compact_json_lines(endpoints), encoding="utf-8")

    print(f"Wrote {len(endpoints)} endpoint definitions to {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
