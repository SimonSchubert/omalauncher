#!/usr/bin/env python3
"""Fail CI if instrumentation never ran, including AGP's successful install-error case."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
reports = list((root / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
if not reports:
    raise SystemExit("No Android instrumentation results were produced")

total = 0
boundary_test_ran = False
for report in reports:
    document = ET.parse(report).getroot()
    suites = [document] if document.tag == "testsuite" else document.findall(".//testsuite")
    for suite in suites:
        if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
            raise SystemExit(f"Android tests failed or were skipped: {report}")
        total += int(suite.get("tests", "0"))
        boundary_test_ran |= any(
            case.get("name") == "prootGuestTransfersBoundCwdAndWritesExternalStorage"
            for case in suite.findall("testcase")
        )
if not total or not boundary_test_ran:
    raise SystemExit("The PRoot integration test did not run")
print(f"{total} Android tests passed, including the PRoot guest boundary")
