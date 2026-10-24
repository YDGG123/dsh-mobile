#!/usr/bin/env python3
"""记录现有真实会话滚动；显式区分模拟器诊断与真机绘制数据，不修改会话。"""

import argparse
import datetime
import json
from pathlib import Path
import signal
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET


def run(command, destination, timeout=30):
    with destination.open("w") as output:
        process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
        try:
            returncode = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # 只终止本次创建的子进程，不影响其它 Instruments 或设备任务。
            process.send_signal(signal.SIGINT)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            raise RuntimeError(f"采集工具超时，已结束本次子进程；请检查 {destination}")
    if returncode:
        raise RuntimeError(f"命令失败；请检查 {destination}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="真实 iPhone UDID 或 CoreDevice ID")
    parser.add_argument("--pid", required=True, type=int, help="已打开真实会话的 App 进程 PID")
    parser.add_argument("--expected-bundle", required=True, help="已安装签名 Debug 包的 Bundle ID")
    parser.add_argument("--output", required=True, type=Path, help="新的证据目录，不能已存在")
    parser.add_argument("--seconds", type=int, default=45)
    parser.add_argument("--label", required=True, help="如 baseline-run1 / optimized-run1")
    parser.add_argument("--simulator", action="store_true", help="明确使用模拟器；仅采 CPU/signpost，不声称测得呈现 FPS")
    arguments = parser.parse_args()
    if not 15 <= arguments.seconds <= 60 or arguments.pid <= 0:
        parser.error("时长须为 15～60 秒且 PID 须大于 0")

    output = arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    if arguments.simulator:
        run(["xcrun", "simctl", "list", "devices", "booted", "--json"], output / "devices.json")
        all_devices = json.loads((output / "devices.json").read_text())["devices"]
        device = next((device for devices in all_devices.values() for device in devices
                       if device.get("udid") == arguments.device and device.get("state") == "Booted"), None)
        if device is None:
            raise RuntimeError("指定模拟器未启动，拒绝采样")
        run(["xcrun", "simctl", "listapps", arguments.device], output / "apps.plist")
        apps = json.loads(subprocess.check_output(["plutil", "-convert", "json", "-o", "-", str(output / "apps.plist")]))
        app = apps.get(arguments.expected_bundle)
        if app is None or not isinstance(app.get("Bundle"), str):
            raise RuntimeError("模拟器没有预期 Bundle 的 App，拒绝采样")
        app_path = Path(urllib.parse.unquote(urllib.parse.urlparse(app["Bundle"]).path))
        executable = Path(subprocess.check_output(["ps", "-ww", "-p", str(arguments.pid), "-o", "comm="]).decode().strip())
        if not app_path.is_absolute() or executable.parent != app_path or app_path.suffix != ".app":
            raise RuntimeError("指定 PID 不属于该模拟器已安装的 App，拒绝采样")
        metadata = {"platform": "simulator", "device_model": device.get("deviceTypeIdentifier"),
                    "measurement": "Time Profiler + os_signpost; simulator graphics instruments unsupported; not rendered FPS"}
        template = "Time Profiler"
    else:
        metadata = validate_physical_device(arguments, output)
        template = "Animation Hitches"
    metadata.update({
        "label": arguments.label,
        "started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "pid": arguments.pid,
        "bundle": arguments.expected_bundle,
        "duration_seconds": arguments.seconds,
        "status": "recording",
    })
    (output / "measurement.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    print("保持当前真实会话；Instruments 开始后，持续滚动到较早内容并触发历史分页。", flush=True)
    run(["xcrun", "xctrace", "record", "--template", template, "--instrument", "os_signpost",
         "--device", arguments.device, "--attach", str(arguments.pid), "--time-limit", f"{arguments.seconds}s",
         "--output", str(output / "history-scroll.trace")], output / "record.log", timeout=arguments.seconds + 60)
    run(["xcrun", "xctrace", "export", "--input", str(output / "history-scroll.trace"),
         "--toc", "--output", str(output / "trace-toc.xml")], output / "export.log")
    schemas = {table.attrib.get("schema", "") for table in ET.parse(output / "trace-toc.xml").iter("table")}
    exported = []
    for schema in sorted(schemas):
        if schema.startswith("hitches-") or "signpost" in schema or schema == "time-profile":
            if not schema.replace("-", "").replace("_", "").isalnum():
                raise RuntimeError("未知的 trace schema 名称，拒绝自动导出")
            run(["xcrun", "xctrace", "export", "--input", str(output / "history-scroll.trace"),
                 "--xpath", f'/trace-toc/run/data/table[@schema="{schema}"]',
                 "--output", str(output / f"{schema}.xml")], output / f"export-{schema}.log")
            exported.append(schema)
    metadata["exported_schemas"] = exported
    metadata["status"] = "recorded-not-yet-evaluated"
    metadata["finished_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    (output / "measurement.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    print(f"已保留原始 trace 和表目录：{output}；须另行核验实际分页、锚点及帧指标。")


def validate_physical_device(arguments, output):
    run(["xcrun", "devicectl", "list", "devices", "--json-output", str(output / "devices.json")],
        output / "device-discovery.log")
    devices = json.loads((output / "devices.json").read_text())["result"]["devices"]
    device = next((device for device in devices if arguments.device in (
        device["identifier"], device.get("hardwareProperties", {}).get("udid"))), None)
    if device is None or device.get("hardwareProperties", {}).get("reality") != "physical":
        raise RuntimeError("目标不是已配对的物理设备，不能进行真实帧率验收")
    if device.get("connectionProperties", {}).get("tunnelState") == "unavailable":
        raise RuntimeError("真实设备当前不可用；请连接、解锁并信任此 Mac 后重试")

    run(["xcrun", "devicectl", "device", "info", "processes", "--device", arguments.device,
         "--json-output", str(output / "processes.json")], output / "process-discovery.log")
    run(["xcrun", "devicectl", "device", "info", "apps", "--device", arguments.device,
         "--json-output", str(output / "apps.json")], output / "app-discovery.log")
    processes = json.loads((output / "processes.json").read_text())["result"]["runningProcesses"]
    apps = json.loads((output / "apps.json").read_text())["result"]["apps"]
    if not isinstance(processes, list) or not isinstance(apps, list):
        raise RuntimeError("设备 JSON 结构未知，拒绝在未验证目标进程时采样")
    process = next((item for item in processes if item.get("processIdentifier") == arguments.pid), None)
    app = next((item for item in apps if item.get("bundleIdentifier") == arguments.expected_bundle), None)
    if process is None or app is None:
        raise RuntimeError("没有找到指定 PID 或预期签名 App，拒绝采样")
    app_path = app.get("path") or app.get("url")
    executable = process.get("executable")
    if not isinstance(app_path, str) or not isinstance(executable, str):
        raise RuntimeError("设备未提供可验证的 App/进程路径，拒绝采样")
    app_path = Path(urllib.parse.unquote(urllib.parse.urlparse(app_path).path))
    executable = Path(urllib.parse.unquote(urllib.parse.urlparse(executable).path))
    if not app_path.is_absolute() or executable.parent != app_path or app_path.suffix != ".app":
        raise RuntimeError("指定 PID 不属于预期 Bundle 的 App 路径，拒绝采样")
    run(["xcrun", "devicectl", "device", "info", "displays", "--device", arguments.device,
         "--json-output", str(output / "displays.json")], output / "displays.log")
    return {
        "platform": "physical-device",
        "device_model": device["hardwareProperties"].get("productType"),
        "os_version": device.get("deviceProperties", {}).get("osVersionNumber"),
        "measurement": "Instruments Animation Hitches; render/presentation evidence, not CADisplayLink callbacks",
    }


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, KeyError, ValueError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
