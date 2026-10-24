#!/usr/bin/env python3
"""Web remote for Vizio TVs. Run: python3 server.py, then open http://localhost:8790

Pairs with any number of TVs (saved in tvs.json), and while it runs it opens each
TV's chosen startup app whenever that TV turns on, whichever remote turned it on.
"""
import json, os, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import tv

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("PORT", 8790))
STORE = os.path.join(ROOT, "tvs.json")
DEVICE_ID = "web-tv-remote"
APPS = {"YouTube": (5, "1")}  # name -> (NAME_SPACE, APP_ID)
lock = threading.Lock()
pending = {}  # host -> pairing challenge while waiting for the PIN


def load_tvs():
    try:
        with open(STORE) as f:
            return json.load(f)
    except FileNotFoundError:  # migrate the single TV paired by tv.py
        tok = tv.load().get("token")
        if not tok:
            return []
        t = {"name": "TV", "host": tv.HOST, "token": tok, "startApp": ""}
        try:
            t["name"] = tv.call("GET", "/state/device/deviceinfo", host=t["host"], token=tok)["ITEMS"][0]["VALUE"]["CAST_NAME"]
        except Exception:
            pass
        return [t]


tvs = load_tvs()


def save():
    with open(STORE, "w") as f:
        json.dump(tvs, f, ensure_ascii=False, indent=1)


def call(t, method, path, body=None):
    r = tv.call(method, path, body, host=t["host"], token=t.get("token"))
    if r.get("STATUS", {}).get("RESULT") not in (None, "SUCCESS"):
        raise RuntimeError(r["STATUS"]["RESULT"])
    return r


def setting(t, path):
    return call(t, "GET", f"/menu_native/dynamic/tv_settings/{path}")["ITEMS"][0]


def modify(t, path, value):
    it = setting(t, path)
    call(t, "PUT", f"/menu_native/dynamic/tv_settings/{path}",
         {"REQUEST": "MODIFY", "HASHVAL": it["HASHVAL"], "VALUE": value})


def power(t):
    return call(t, "GET", "/state/device/power_mode")["ITEMS"][0]["VALUE"]


def launch(t, name):
    ns, app_id = APPS[name]
    call(t, "PUT", "/app/launch", {"VALUE": {"MESSAGE": None, "NAME_SPACE": ns, "APP_ID": app_id}})


def state(t):
    inputs = []
    for i in call(t, "GET", "/menu_native/dynamic/tv_settings/devices/name_input")["ITEMS"]:
        inputs.append({"name": i["NAME"], "label": (i.get("VALUE") or {}).get("NAME", i["NAME"])})
    return {"power": power(t), "volume": setting(t, "audio/volume")["VALUE"],
            "input": setting(t, "devices/current_input")["VALUE"], "inputs": inputs}


def watcher():
    """Open each TV's startup app when it goes from off/unreachable to on."""
    last = {}
    while True:
        for t in list(tvs):
            if not t.get("startApp"):
                last.pop(t["host"], None)
                continue
            try:
                on = power(t) == 1
            except Exception:
                on = False
            if on and last.get(t["host"]) is False:
                time.sleep(5)  # let the home screen finish loading
                try:
                    launch(t, t["startApp"])
                except Exception:
                    pass
            last[t["host"]] = on
        time.sleep(3)


class H(BaseHTTPRequestHandler):
    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(data)

    def tv(self, q):
        return tvs[int(q.get("tv", ["0"])[0])]

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            if u.path == "/api/tvs":
                self.send(200, {"tvs": [{"name": t["name"], "startApp": t.get("startApp", "")} for t in tvs],
                                "apps": list(APPS)})
            elif u.path == "/api/state":
                self.send(200, state(self.tv(q)))
            elif u.path == "/icon.svg":
                with open(os.path.join(ROOT, "icon.svg"), "rb") as f:
                    self.send(200, f.read(), "image/svg+xml")
            else:
                with open(os.path.join(ROOT, "remote.html"), "rb") as f:
                    self.send(200, f.read(), "text/html; charset=utf-8")
        except Exception as e:
            self.send(502, {"error": str(e)})

    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
        try:
            if u.path == "/api/pair/start":
                ip = body["ip"].strip()
                host = ip if ":" in ip else ip + ":7345"
                t = {"host": host}
                req = {"DEVICE_ID": DEVICE_ID, "DEVICE_NAME": "Web Remote"}
                try:
                    it = call(t, "PUT", "/pairing/start", req)["ITEM"]
                except RuntimeError as e:
                    if str(e) != "BLOCKED":
                        raise
                    # A previous pairing is still open on the TV: cancel it and try again
                    try:
                        call(t, "PUT", "/pairing/cancel", {"DEVICE_ID": DEVICE_ID, "CHALLENGE_TYPE": 1,
                                                           "RESPONSE_VALUE": "1111", "PAIRING_REQ_TOKEN": 0})
                    except Exception:
                        pass
                    time.sleep(1)
                    it = call(t, "PUT", "/pairing/start", req)["ITEM"]
                pending[host] = it
                return self.send(200, {"host": host})
            if u.path == "/api/pair/finish":
                host = body["host"]
                it = pending.pop(host)
                t = {"host": host}
                r = call(t, "PUT", "/pairing/pair", {"DEVICE_ID": DEVICE_ID, "CHALLENGE_TYPE": it["CHALLENGE_TYPE"],
                                                     "RESPONSE_VALUE": body["pin"].strip(),
                                                     "PAIRING_REQ_TOKEN": it["PAIRING_REQ_TOKEN"]})
                t["token"] = r["ITEM"]["AUTH_TOKEN"]
                try:
                    t["name"] = call(t, "GET", "/state/device/deviceinfo")["ITEMS"][0]["VALUE"]["CAST_NAME"]
                except Exception:
                    t["name"] = f"TV {len(tvs) + 1}"
                t["startApp"] = ""
                with lock:
                    tvs[:] = [x for x in tvs if x["host"] != host] + [t]  # re-pairing replaces
                    save()
                return self.send(200, {"index": len(tvs) - 1})
            if u.path == "/api/remove":
                with lock:
                    del tvs[int(q["tv"][0])]
                    save()
                return self.send(200, {"ok": True})
            if u.path == "/api/startapp":
                with lock:
                    self.tv(q)["startApp"] = body["value"] if body["value"] in APPS else ""
                    save()
                return self.send(200, {"ok": True})

            t = self.tv(q)
            if u.path == "/api/key" and body.get("key") in tv.KEYS:
                cs, code = tv.KEYS[body["key"]]
                call(t, "PUT", "/key_command/", {"KEYLIST": [{"CODESET": cs, "CODE": code, "ACTION": "KEYPRESS"}]})
            elif u.path == "/api/volume":
                modify(t, "audio/volume", int(body["value"]))
            elif u.path == "/api/input":
                modify(t, "devices/current_input", body["value"])
            elif u.path == "/api/app":
                launch(t, body["name"])
            else:
                return self.send(400, {"error": "bad request"})
            self.send(200, {"ok": True})
        except Exception as e:
            self.send(502, {"error": str(e)})

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    if tvs and not os.path.exists(STORE):
        save()
    threading.Thread(target=watcher, daemon=True).start()
    print(f"Remote at http://localhost:{PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
