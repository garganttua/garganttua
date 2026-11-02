#!/usr/bin/env python3
"""The native proofs' HTTP scenario: what the application must answer, whatever runs it and whatever
store it runs on — the PostgreSQL and the MongoDB proof play the same checks.

Run against the JVM or the native binary alike — the answers must be the same:

    python3 api/e2e/e2e_scenario.py http://localhost:7171

Every check prints one line; the exit code is the number of failed checks.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:7171").rstrip("/") + "/sites"
PARIS = [2.3522, 48.8566]
LYON = [4.8357, 45.7640]
failures = []


def call(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def find(filter_json):
    status, body = call("GET", BASE + "?filter=" + urllib.parse.quote(json.dumps(filter_json)))
    return status, (json.loads(body) if status == 200 else body)


def rows(payload):
    """A read answers a list, or a page carrying one."""
    if isinstance(payload, list):
        return payload
    for key in ("content", "items", "data", "results"):
        if isinstance(payload, dict) and isinstance(payload.get(key), list):
            return payload[key]
    return payload


def check(name, condition, detail=""):
    print(("PASS " if condition else "FAIL ") + name + ("" if condition else "  -- " + str(detail)[:600]))
    if not condition:
        failures.append(name)


def site(code, name, city, coordinates, tags):
    return {"code": code, "name": name, "address": {"city": city, "zip": "00000"}, "tags": tags,
            "extra": {"level": 3, "labels": ["a", "b"], "nested": {"ok": True}},
            "location": {"type": "Point", "coordinates": coordinates}}


status, body = call("POST", BASE, site("PAR", "Paris centre", "Paris", PARIS, ["capital", "north"]))
check("create Paris -> 201", status == 201, (status, body))
status, body = call("POST", BASE, site("LYS", "Lyon gare", "Lyon", LYON, ["south"]))
check("create Lyon -> 201", status == 201, (status, body))

status, body = call("POST", BASE, site("PAR", "Paris bis", "Paris", PARIS, []))
check("duplicate code -> 409 (unique index, refused by the database)", status == 409, (status, body))

status, body = call("GET", BASE)
listed = rows(json.loads(body)) if status == 200 else []
check("read all -> 200, two sites", status == 200 and len(listed) == 2, (status, body))
paris = next((s for s in listed if s.get("code") == "PAR"), {})
check("flattened POJO round-trips", paris.get("address", {}).get("city") == "Paris", paris)
check("list (child table) round-trips", paris.get("tags") == ["capital", "north"], paris)
check("untyped value (JSONB) round-trips",
      paris.get("extra") == {"level": 3, "labels": ["a", "b"], "nested": {"ok": True}}, paris)
location = paris.get("location") or {}
check("geometry (PostGIS) round-trips as GeoJSON",
      location.get("type") == "Point" and [round(c, 4) for c in location.get("coordinates", [])] == [2.3522, 48.8566],
      paris)

status, found = find({"name": {"$regex": "^Lyon\\s+g"}})
check("$regex (PCRE translated)", status == 200 and [s["code"] for s in rows(found)] == ["LYS"], found)

square = {"type": "Polygon", "coordinates": [[[2, 48.5], [2.7, 48.5], [2.7, 49.1], [2, 49.1], [2, 48.5]]]}
status, found = find({"location": {"$geoWithin": square}})
check("$geoWithin (on the sphere)", status == 200 and [s["code"] for s in rows(found)] == ["PAR"], found)

status, found = find({"location": {"$near": {"$geometry": {"type": "Point", "coordinates": LYON},
                                             "$maxDistance": 500000}}})
check("$near -> nearest first, within the radius",
      status == 200 and [s["code"] for s in rows(found)] == ["LYS", "PAR"], found)

status, found = find({"location": {"$near": {"$geometry": {"type": "Point", "coordinates": LYON},
                                             "$maxDistance": 50000}}})
check("$near $maxDistance in metres", status == 200 and [s["code"] for s in rows(found)] == ["LYS"], found)

status, body = call("GET", BASE + "?size=1&page=0&filter=" + urllib.parse.quote(json.dumps(
    {"location": {"$near": {"$geometry": {"type": "Point", "coordinates": LYON}, "$maxDistance": 500000}}})))
page = json.loads(body) if status == 200 else {}
check("$near paginated -> the nearest, and the count of the whole ring",
      status == 200 and page.get("totalCount") == 2 and [s["code"] for s in rows(page.get("entities", []))] == ["LYS"],
      (status, body))

print("%d check(s) failed" % len(failures) if failures else "ALL CHECKS PASSED")
sys.exit(len(failures))
