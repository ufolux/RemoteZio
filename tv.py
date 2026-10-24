#!/usr/bin/env python3
"""Control a Vizio SmartCast TV over the local network (no dependencies).

Usage:
  python3 tv.py pair            # shows PIN on TV
  python3 tv.py pin 1234        # finish pairing, saves token
  python3 tv.py power|on|off|vol+|vol-|mute|up|down|left|right|ok|back|home|menu
  python3 tv.py volume [N]      # get / set volume
  python3 tv.py input [NAME]    # list / switch input (e.g. hdmi1)
  python3 tv.py state
  python3 tv.py app youtube     # open an app (youtube, netflix, prime)
  python3 tv.py watch youtube   # keep running: open the app every time the TV turns on
"""
import json, os, ssl, sys, urllib.request

HOST = os.environ.get("TV_HOST", "100.110.187.213:7345")
CONF = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".tv_auth.json")
CTX = ssl._create_unverified_context()  # TV uses a self-signed cert
DEVICE_ID = "mac-tv-remote"

KEYS = {  # (codeset, code)
    "vol-": (5, 0), "vol+": (5, 1), "mute": (5, 4),
    "off": (11, 0), "on": (11, 1), "power": (11, 2),
    "down": (3, 0), "left": (3, 1), "ok": (3, 2), "up": (3, 8), "right": (3, 7),
    "back": (4, 0), "home": (4, 15), "menu": (4, 8), "info": (4, 6),
    "ch-": (8, 0), "ch+": (8, 1),
}


APPS = {"youtube": (5, "1"), "netflix": (3, "1"), "prime": (3, "3")}  # (NAME_SPACE, APP_ID)


def load():
    try:
        with open(CONF) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def call(method, path, body=None, host=None, token=None):
    """host/token default to this script's own TV and saved pairing."""
    headers = {"Content-Type": "application/json"}
    if tok := (token if host else load().get("token")):
        headers["AUTH"] = tok
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"https://{host or HOST}{path}", data, headers, method=method)
    with urllib.request.urlopen(req, context=CTX, timeout=5) as r:
        return json.load(r)


def setting(path):
    items = call("GET", f"/menu_native/dynamic/tv_settings/{path}")["ITEMS"]
    return items[0]


def main(a):
    cmd = a[0] if a else "state"
    if cmd == "pair":
        r = call("PUT", "/pairing/start", {"DEVICE_ID": DEVICE_ID, "DEVICE_NAME": "Mac Remote"})
        item = r["ITEM"]
        with open(CONF, "w") as f:
            json.dump({"req": item["PAIRING_REQ_TOKEN"], "chal": item["CHALLENGE_TYPE"]}, f)
        print("PIN shown on TV. Run: python3 tv.py pin XXXX")
    elif cmd == "pin":
        c = load()
        r = call("PUT", "/pairing/pair", {"DEVICE_ID": DEVICE_ID, "CHALLENGE_TYPE": c["chal"],
                                          "RESPONSE_VALUE": a[1], "PAIRING_REQ_TOKEN": c["req"]})
        with open(CONF, "w") as f:
            json.dump({"token": r["ITEM"]["AUTH_TOKEN"]}, f)
        print("Paired.")
    elif cmd in KEYS:
        cs, code = KEYS[cmd]
        call("PUT", "/key_command/", {"KEYLIST": [{"CODESET": cs, "CODE": code, "ACTION": "KEYPRESS"}]})
    elif cmd == "volume":
        it = setting("audio/volume")
        if len(a) > 1:
            call("PUT", "/menu_native/dynamic/tv_settings/audio/volume",
                 {"REQUEST": "MODIFY", "HASHVAL": it["HASHVAL"], "VALUE": int(a[1])})
        else:
            print(it["VALUE"])
    elif cmd == "input":
        cur = call("GET", "/menu_native/dynamic/tv_settings/devices/current_input")["ITEMS"][0]
        if len(a) > 1:
            call("PUT", "/menu_native/dynamic/tv_settings/devices/current_input",
                 {"REQUEST": "MODIFY", "HASHVAL": cur["HASHVAL"], "VALUE": a[1]})
        else:
            print("current:", cur["VALUE"])
            for i in call("GET", "/menu_native/dynamic/tv_settings/devices/name_input")["ITEMS"]:
                print(" ", i["NAME"], "-", i["VALUE"].get("NAME", ""))
    elif cmd == "app":
        ns, app_id = APPS[a[1].lower()]
        call("PUT", "/app/launch", {"VALUE": {"MESSAGE": None, "NAME_SPACE": ns, "APP_ID": app_id}})
    elif cmd == "watch":  # open an app whenever the TV turns on (any remote)
        import time
        last = None
        while True:
            try:
                on = call("GET", "/state/device/power_mode")["ITEMS"][0]["VALUE"] == 1
                if on and last is False:
                    time.sleep(5)  # let the home screen finish loading
                    main(["app", a[1]])
                    print(time.strftime("%H:%M:%S"), "TV on -> opened", a[1], flush=True)
                last = on
            except Exception:
                last = False if last is None else last  # TV unreachable while fully off
            time.sleep(3)
    elif cmd == "state":
        print("power:", call("GET", "/state/device/power_mode")["ITEMS"][0]["VALUE"])
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
