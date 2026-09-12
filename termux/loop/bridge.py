#!/usr/bin/env python3
"""Local PhoneUI adapter; runs only the project/config selected at startup."""
import argparse
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

CAPABILITIES = {
    "project.files.read": "READ", "project.files.write": "WRITE",
    "project.build": "ACTION", "device.logs.read": "READ",
    "workspace.screenshot": "READ", "android.install.debug": "PRIVILEGED",
}


class Jobs:
    def __init__(self, workspace, command):
        self.workspace = str(Path(workspace).resolve())
        self.command = command
        self.jobs = {}
        self.lock = threading.RLock()

    def event(self, job, message, kind="STATUS"):
        with self.lock:
            job["events"].append(dict(sequence=len(job["events"]) + 1,
                at=int(time.time() * 1000), kind=kind, message=message[:8192]))

    def start(self, request):
        identifier = request.get("jobId", "")
        if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", identifier):
            raise ValueError("Invalid jobId")
        if request.get("projectRoot") != self.workspace:
            raise ValueError("Project must match the configured workspace exactly")
        grants = request.get("grantedCapabilities", [])
        if not isinstance(grants, list) or not all(isinstance(x, str) for x in grants):
            raise ValueError("Invalid grants")
        if not set(CAPABILITIES).issubset(grants):
            raise ValueError("Approve the project build, repair, install and observation capabilities first")
        with self.lock:
            if identifier in self.jobs:
                raise ValueError("jobId already used")
            if any(j["status"] == "RUNNING" or
                   (j["process"] is not None and j["process"].poll() is None)
                   for j in self.jobs.values()):
                raise ValueError("A job is already running")
            job = dict(status="RUNNING", events=[], process=None, error=None)
            self.jobs[identifier] = job
            self.event(job, "Starting configured Termux solving loop")
            threading.Thread(target=self.run, args=(job,), daemon=True).start()
        return {"jobId": identifier, "status": "RUNNING"}

    def run(self, job):
        try:
            with self.lock:
                if job["status"] != "RUNNING":
                    return
                job["process"] = subprocess.Popen(self.command, cwd=self.workspace,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                    encoding="utf-8", errors="replace", start_new_session=(os.name != "nt"))
            final_json = False
            for line in job["process"].stdout:
                text = line.strip()
                if text == "{":
                    final_json = True  # Full result remains on disk; preserve useful PhoneUI events.
                if not text or final_json:
                    continue
                try:
                    progress = json.loads(text)
                except ValueError:
                    self.event(job, text, "OUTPUT")
                    continue
                if "attempt" in progress:
                    message = f"Attempt {progress['attempt']}: "
                    message += progress.get("stage", progress.get("failure") or progress.get("status", ""))
                    self.event(job, message, "ERROR" if progress.get("failure") else "STATUS")
                    if progress.get("context"):
                        self.event(job, progress["context"], "ARTIFACT")
            job["process"].stdout.close()
            code = job["process"].wait()
            with self.lock:
                if job["status"] == "RUNNING":
                    job["status"] = "COMPLETED" if code == 0 else "FAILED"
                    job["error"] = None if code == 0 else f"Loop exited {code}; inspect run artifacts"
                    self.event(job, "Loop verified successfully" if code == 0 else job["error"],
                               "STATUS" if code == 0 else "ERROR")
        except Exception as exc:
            with self.lock:
                job["status"] = "FAILED"
                job["error"] = str(exc)
                self.event(job, str(exc), "ERROR")

    def cancel(self, identifier):
        with self.lock:
            job = self.jobs[identifier]
            if job["status"] == "RUNNING":
                job["status"] = "FAILED"  # Existing PhoneUI polling understands FAILED.
                job["error"] = "Cancelled by user"
                process = job["process"]
                if process and process.poll() is None:
                    if os.name == "nt":
                        process.terminate()
                    else:
                        os.killpg(process.pid, signal.SIGTERM)
                self.event(job, "Cancellation requested", "ERROR")
        return {"status": job["status"]}

    def events(self, identifier, after):
        with self.lock:
            job = self.jobs[identifier]
            return dict(status=job["status"], error=job["error"],
                        events=[e for e in job["events"] if e["sequence"] > after])


def handler(jobs, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Do not log the token-bearing URL.

        def reply(self, code, value):
            data = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def dispatch(self):
            parsed = urlsplit(self.path)
            parts = parsed.path.split("/")
            if len(parts) < 3 or not hmac.compare_digest(parts[1], token):
                return self.reply(403, {"error": "Invalid bridge token"})
            path = "/" + "/".join(parts[2:])
            try:
                if self.command == "GET" and path == "/v1/health":
                    return self.reply(200, {"ok": True})
                if self.command == "GET" and path == "/v1/capabilities":
                    return self.reply(200, dict(providerId="termux-gradle-loop",
                        displayName="Termux / Gradle solving loop", capabilities=[
                            dict(id=k, risk=v, description=k) for k, v in CAPABILITIES.items()]))
                if self.command == "POST":
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 0 <= length <= 16384:
                        raise ValueError("Request body too large")
                    body = json.loads(self.rfile.read(length) or b"{}")
                    if not isinstance(body, dict):
                        raise ValueError("Expected JSON object")
                    if path == "/v1/jobs":
                        return self.reply(202, jobs.start(body))
                    match = re.fullmatch(r"/v1/jobs/([A-Za-z0-9_-]+)/cancel", path)
                    if match:
                        return self.reply(200, jobs.cancel(match[1]))
                if self.command == "GET":
                    match = re.fullmatch(r"/v1/jobs/([A-Za-z0-9_-]+)/events", path)
                    if match:
                        after = int(parse_qs(parsed.query).get("after", ["0"])[0])
                        return self.reply(200, jobs.events(match[1], after))
                self.reply(404, {"error": "Unknown endpoint"})
            except KeyError:
                self.reply(404, {"error": "Unknown job"})
            except (ValueError, TypeError) as exc:
                self.reply(400, {"error": str(exc)})

        do_GET = dispatch
        do_POST = dispatch
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--display", type=int)
    parser.add_argument("--demo-solver", action="store_true")
    args = parser.parse_args()
    workspace = args.workspace.resolve(strict=True)
    config = args.config.resolve(strict=True)
    command = [sys.executable, "-u", str(Path(__file__).with_name("solve_loop.py")),
               "--workspace", str(workspace), "--config", str(config),
               "--serial", args.serial, "--max-attempts", str(args.max_attempts)]
    if args.display is not None:
        command += ["--display", str(args.display)]
    if args.demo_solver:
        command += ["--demo-solver"]
    token = secrets.token_urlsafe(24)
    jobs = Jobs(workspace, command)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(jobs, token))
    print(f"PhoneUI bridge URL: http://127.0.0.1:{args.port}/{token}", flush=True)
    print(f"Approved project path: {workspace}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        for identifier in list(jobs.jobs):
            jobs.cancel(identifier)
        server.server_close()


if __name__ == "__main__":
    main()
