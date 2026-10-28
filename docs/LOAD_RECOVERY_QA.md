# Native server-load recovery

Use this checklist on iOS and Android, including a cold launch with a saved active
server. It describes expected behavior, not completed device testing. Use a test
instance whose API health check succeeds and whose main document can be configured
for each failure. No real credentials are needed.

| Main document | Expected result |
| --- | --- |
| HTTP 404 or 503 | Native error controls offer Retry and Servers. |
| DNS failure, refused connection, or invalid TLS | Native recovery appears; TLS validation is never bypassed. |
| Never completes | Recovery appears within 12 seconds on iOS / 20 seconds on Android. |
| HTTP 200 with a blank body or failing app module | Recovery deadline remains active until the app mounts. |
| Working chat app | No recovery controls remain over the chat. |
| External SSO page without a chat root | Loads normally; no false failure. |
| Image/API/subframe 404 | Does not replace the working app with recovery. |

Also verify:

1. Restore the failed server, tap Retry, and confirm the chat loads.
2. Tap Servers after a failure; the bundled picker opens and retains saved entries.
3. Reconnect to a previously authenticated entry; its session is preserved.
4. Retry or navigate away while a slow request is pending; late callbacks from
   that request must not reopen recovery over the new page.
5. On Android, Back during loading or failure returns to the picker. Normal
   chat Back behavior still dismisses overlays and walks history.
6. Check both portrait and landscape, large text, and system navigation bars.
