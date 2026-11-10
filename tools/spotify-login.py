#!/usr/bin/env python3
"""
Connects your Spotify account to Repsac Launcher, so it can show what you're playing.

Run it once on your computer (Python 3, nothing to install):

    python3 tools/spotify-login.py --client-id <YOUR_CLIENT_ID> --device <TV-IP>:<PORT>

It opens Spotify's login page in your browser, and when you've approved, it sends the login
to the TV over adb. Nothing is saved on your computer or in this project.

Before the first run, create a free app at https://developer.spotify.com/dashboard:
  - Redirect URI:  http://127.0.0.1:8888/callback
  - API:           Web API
Then copy its Client ID. See the README for details.
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
import secrets
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-read-playback-state user-read-currently-playing"
PACKAGE = "no.repsac.launcher"


def main():
    ap = argparse.ArgumentParser(description="Connect Spotify to Repsac Launcher")
    ap.add_argument("--client-id", default=os.environ.get("SPOTIFY_CLIENT_ID"),
                    help="Client ID of your app at developer.spotify.com")
    ap.add_argument("--device", required=True, help="The TV's ip:port from Wireless debugging")
    args = ap.parse_args()
    if not args.client_id:
        sys.exit("Missing --client-id (or the SPOTIFY_CLIENT_ID environment variable).")

    # 1) Log in with PKCE: no client secret needed
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": args.client_id,
        "response_type": "code",
        "redirect_uri": REDIRECT,
        "scope": SCOPES,
        "code_challenge_method": "S256",
        "code_challenge": challenge,
        "state": state,
    })

    result = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [None])[0] != state:
                result["error"] = "state mismatch"
            elif "error" in q:
                result["error"] = q["error"][0]
            else:
                result["code"] = q.get("code", [None])[0]
            ok = "code" in result
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            msg = "Done! You can close this tab and go back to the terminal." if ok else \
                  "Something went wrong: " + result.get("error", "unknown")
            self.wfile.write(f"<html><body style='font-family:sans-serif;padding:40px'><h2>{msg}</h2></body></html>".encode())

        def log_message(self, *a):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 8888), Callback)
    print("Opening Spotify in your browser. If it doesn't open, go to:\n\n" + url + "\n")
    webbrowser.open(url)
    while not result:
        server.handle_request()
    server.server_close()
    if "code" not in result:
        sys.exit("Login failed: " + result.get("error", "unknown"))

    # 2) Exchange the code for tokens
    data = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": result["code"],
        "redirect_uri": REDIRECT,
        "client_id": args.client_id,
        "code_verifier": verifier,
    }).encode()
    try:
        req = urllib.request.Request("https://accounts.spotify.com/api/token", data=data,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
        tokens = json.load(urllib.request.urlopen(req, timeout=15))
    except urllib.error.URLError as e:
        hint = ""
        if "CERTIFICATE_VERIFY_FAILED" in str(e):
            hint = ("\nPython is missing its certificates. On a Mac with Python from python.org, run "
                    "'Install Certificates.command' in the Python folder under Applications, then try again.")
        sys.exit(f"Couldn't get the token from Spotify: {e}{hint}")
    refresh = tokens.get("refresh_token")
    if not refresh:
        sys.exit("Spotify didn't return a refresh token: " + json.dumps(tokens))

    # 3) Hand it to the TV (only adb can deliver this; see SpotifyLoginReceiver)
    dev = args.device if ":" in args.device else args.device + ":5555"
    subprocess.run(["adb", "connect", dev], capture_output=True)
    cmd = ["adb", "-s", dev, "shell", "am", "broadcast",
           "-a", f"{PACKAGE}.SPOTIFY_LOGIN", "-n", f"{PACKAGE}/.SpotifyLoginReceiver",
           "--es", "client_id", args.client_id, "--es", "refresh_token", refresh]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if "saved" in out.stdout:
        print("✓ Spotify is connected. Play something and press Home on the remote.")
    else:
        print("Couldn't send the login to the TV. adb said:\n" + (out.stdout + out.stderr).strip())
        print("Check the ip:port under Wireless debugging, that Repsac Launcher is installed, and try again.")
        sys.exit(1)


if __name__ == "__main__":
    main()
