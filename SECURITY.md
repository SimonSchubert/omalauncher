# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Use GitHub's private vulnerability reporting: open the **Security** tab of this
repository and choose **Report a vulnerability**. That starts a private advisory
only the maintainers can see. If the button is not there, open an issue that
says only that you have a security report and asks for a private channel; leave
the details out of it.

A useful report includes:

- the Omalauncher version and versionCode (settings › about), and where it came
  from (Google Play or the APK on the releases page),
- the device and Android version,
- the steps to reproduce, and what an attacker could do,
- logs or a proof of concept, if you have them.

## What is in scope

Omalauncher's own code: the Android app, the OWL compiler and runtime, and the
Wayland bridge.

The Linux guest is Arch Linux ARM running under proot. **proot is not a
sandbox**: programs in the guest have the launcher's Android permissions, and
the launcher's files are reachable from it (see the README). A report that the
guest can read launcher data is therefore expected behavior, not a
vulnerability. Problems in Arch packages, proot, or the coding agents belong to
those projects.

## Supported versions

Only the latest release is supported; fixes ship in the next one.
