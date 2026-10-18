# Pilot web deployment

Scope: static Android download, setup instructions, recorded verification and milestone status. The page never claims a live branch connection or supplies cashier/kitchen transaction authority. Owner authentication, cloud reporting and remote management remain Milestone 3+ work.

Hosting target: the new `theplugos-new` Vercel project linked to `PDthePlug/ThePlugOs-new`, production branch `main`, existing owner team `denvermpofu09-2813s-projects`. Other Vercel projects are unchanged.

Source: `web/`, `scripts/build-web.mjs`, `vercel.json`. Build output is a whitelist of static HTML/CSS/JS, the verified training APK, public release metadata and five operating documents. Android source, database files, environment files and test fixture private keys are not web output. No external libraries, analytics, API routes or environment secrets are used.

Run `node scripts/build-web.mjs` to build. It refuses to publish if the committed Android APK differs from the release manifest's SHA-256 digest. Review and update the native build, tests, source commit, version and digest together for future Android releases. The Vercel root `vercel.json` declares the build/output and response headers, including CSP, disabled embedding, nosniff and APK attachment disposition.

The publication uses the authenticated GitHub connector because the shell Git transport has no account credentials. The published native source tree `eaa5b110f66e4dfe0d4478843126077f2939e997` exactly matches the owner-approved local commit `025cc51`; it is reachable as remote commit `37b7ebbc8c223776ff470fb3164e55caaa1510ff`.
