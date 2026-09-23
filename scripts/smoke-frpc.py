"""Run real Android frpc integration tests using a loopback-only, ephemeral frps.

Build/install app and androidTest APKs first. Use an isolated debug application ID;
the script refuses the regular user-facing package. Requires Go, adb, built frp source.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def run(args):
    root = Path(__file__).resolve().parents[1]
    if args.package in {"com.powercess.mbrain", "com.powercess.mbrain.debug"}:
        raise ValueError("Use an isolated QA package to protect existing application data")
    adb = [args.adb, "-s", args.serial]
    source = root / "build/frpc-source/frp-0.71.0"
    dashboard = source / "web/frps/dist"
    dashboard.mkdir(parents=True, exist_ok=True)
    (dashboard / "index.html").write_text("<!doctype html><title>Local test</title>", encoding="utf-8")
    binary = source / ("frps-test.exe" if os.name == "nt" else "frps-test")
    subprocess.run(["go", "build", "-trimpath", "-buildvcs=false", "-tags=frps", "-o", str(binary), "./cmd/frps"], cwd=source, check=True)
    ports = {}
    while len(ports) < 3:
        candidate = free_port()
        if candidate not in ports.values():
            ports[["controlPort", "tcpPort", "mcpPort"][len(ports)]] = candidate
    with tempfile.TemporaryDirectory(prefix="mbrain-frpc-test-") as temporary:
        directory = Path(temporary)
        token = secrets.token_urlsafe(24)
        config = {
            "bindAddr": "127.0.0.1", "bindPort": ports["controlPort"], "proxyBindAddr": "127.0.0.1",
            "auth": {"method": "token", "token": token},
            "transport": {"tls": {"force": True}},
            "allowPorts": [{"single": ports["tcpPort"]}, {"single": ports["mcpPort"]}],
        }
        config_file = directory / "frps.json"
        config_file.write_text(json.dumps(config), encoding="utf-8")
        with (directory / "frps.log").open("w") as log:
            process = subprocess.Popen([str(binary), "-c", str(config_file)], stdout=log, stderr=log)
            mapped = []
            try:
                deadline = time.monotonic() + 15
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Local frps failed to start")
                    try:
                        with socket.create_connection(("127.0.0.1", ports["controlPort"]), timeout=1):
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise RuntimeError("Local frps did not become ready")
                        time.sleep(.2)
                for port in ports.values():
                    subprocess.run(adb + ["reverse", f"tcp:{port}", f"tcp:{port}"], check=True, stdout=subprocess.DEVNULL)
                    mapped.append(port)
                subprocess.run(adb + ["shell", "am", "start", "-n", args.package + "/com.powercess.mbrain.MainActivity"], check=True, stdout=subprocess.DEVNULL)
                time.sleep(1)
                command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", "com.powercess.mbrain.remote.FrpcDeviceTest"]
                parameters = dict(ports, frpToken=token)
                for name, value in parameters.items():
                    command += ["-e", name, str(value)]
                command += [args.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
                result = subprocess.run(command, capture_output=True, text=True, timeout=240)
                print(result.stdout)
                if result.returncode or "OK (1 test)" not in result.stdout:
                    raise RuntimeError("Device frpc integration test failed (see instrumentation output)")
            finally:
                subprocess.run(adb + ["shell", "am", "force-stop", args.package], stdout=subprocess.DEVNULL)
                for port in mapped:
                    subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], stdout=subprocess.DEVNULL)
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="com.powercess.mbrain.frpcqa")
    run(parser.parse_args())
