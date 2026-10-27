# Ketch iOS: UC WebKit login + authenticated download handoff (prototype)

Target: `linroid/Ketch`, branch `main`, inspected near commit `00e5ee2f98b02c9152ac5fcd707b495a22af5155` (Oct 2026).

## What it does

- Adds a **UC** button to Ketch's iOS view and a built-in `WKWebView` showing `https://drive.uc.cn/`.
- Uses `WKWebsiteDataStore.default()` so the sign-in persists between launches of **Ketch** (not Safari).
- On user-requested transfer of an HTTPS URL ending in `uc.cn`, retrieves cookies that match the **actual download URL's host and path**, the browser user agent, and the referrer, then pre-fills Ketch's existing download sheet.
- Keeps tasks with sensitive captured headers on the **local** Ketch instance, not a paired remote server.
- Detects ordinary HTTP attachment responses. For JS/fetch/blob-generated URLs, use the manual **Send to Ketch** button on an opened direct link.

## Apply and build

1. Clone https://github.com/linroid/Ketch on a Mac with **Xcode** and required Gradle/JDK tooling.
2. From the repository root run `git apply --check /path/to/ketch-uc-browser.patch` then `git apply /path/to/ketch-uc-browser.patch`.
3. Open `app/ios/Ketch.xcodeproj`, set your own Apple Development team, and build on a physical iPad or make an unsigned IPA for signing/sideloading.
4. Open **UC** in Ketch, sign in at the official `drive.uc.cn` website, open a direct UC download link, and tap **Send to Ketch** when needed. Configure 1 connection first; only then test parallel connections.

## Caveats / security

- This is an **uncompiled prototype**. It has not been run on an iPad and may need adjustments for Ketch changes.
- UC may reject an embedded browser, may use JavaScript/fetch requests instead of top-level navigations, or may issue short-lived signed URLs. The downloader does not gain privileges beyond your own account. Server-side restrictions or 403 errors are not bypassed.
- Ketch stores downloaded-task headers, including cookies, as part of the task data. **Do not share your Ketch data folder, logs, or raw tasks.** The patch does not add encrypted credential storage, automatic cookie refresh for resumed tasks, or logout/clear-session controls.
- Site data in `WKWebsiteDataStore.default()` is inside Ketch and is **not** shared with Safari.
- Domain-specific cookies are **not** sent across unrelated hosts. Downloads on other domains do not receive UC credentials.
- For production use, add an authenticated request broker / short-lived token lifecycle rather than persisting cookie headers with long-lived tasks, and implement safer handling of HTTP redirects.

If `git apply --check` fails because your Ketch checkout has changed, pin to the inspected commit and rebase the patch.
