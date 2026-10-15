# Security Policy

## Supported versions

Only the latest release receives security fixes.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

**Please do not report security vulnerabilities in public issues.**

Report them privately through GitHub: open the repository's **Security** tab and click **Report a vulnerability**.

Please include:

- the plugin version and the engine version (for example *OIE 4.6.0*),
- a description of the problem and its impact,
- the steps to reproduce it.

Do not include patient data or other confidential information in the report.

You can expect a first answer within 7 days. Once the problem is confirmed, a fixed release will be published and
the advisory will credit you, unless you prefer otherwise.

## Scope

Dependency Explorer is read-only: it reads channel, code template, global script and Configuration Map definitions
and never changes them. Its REST endpoints (`/api/extensions/depexplorer/...`) require a logged-in user with the
*View Dependency Explorer* permission. Note that the results can show parts of scripts and connector settings, so
grant that permission only to users who may see the configuration.

The client jars are signed with a self-signed certificate. This is a known limitation, not a vulnerability; see
the README.
