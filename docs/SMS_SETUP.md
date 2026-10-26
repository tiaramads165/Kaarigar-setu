# Sending one-time codes by SMS

By default codes are only written to the server log (`SMS_PROVIDER=console`): fine for development and a closed pilot, not for real users.
`SMS_PROVIDER=http` sends them through any SMS gateway that takes an HTTP POST. Nothing about a provider is hard-coded; you describe the
request in environment variables (in `.env.local` for development, the deployment's secret store for production). The key is never committed.

| Variable | Meaning |
|---|---|
| `SMS_PROVIDER` | `console` (default) or `http` |
| `SMS_URL` | the gateway endpoint (must be `https://` when `APP_ENV=prod`) |
| `SMS_AUTH_HEADER` / `SMS_AUTH_VALUE` | header name (default `Authorization`) and its value: **the secret**. Leave the name empty if your gateway wants the key in the URL or body |
| `SMS_CONTENT_TYPE` | `json` (default) or `form` |
| `SMS_BODY` | the request body, with placeholders `{to}` (+91XXXXXXXXXX), `{to_digits}` (91XXXXXXXXXX), `{to_local}` (10 digits), `{message}`, `{sender}`. Values are escaped for the chosen format |
| `SMS_SENDER` | sender id (India: your DLT-registered header) |
| `SMS_MESSAGE` | wording; must contain `{code}`. Default: "Your Kaarigar Setu code is {code}. It expires in 5 minutes. Do not share it with anyone." |

## Behaviour you can rely on
* A failed send (gateway error, timeout, no network) is **logged and never shown to the caller**: login code requests answer the same way
  whether or not an account exists, and an error would reveal which numbers are registered. Look for `[SMS] SEND FAILED` in the server log.
  The code itself is never logged in `http` mode, only a masked number and the HTTP status.
* In `APP_ENV=prod` the app refuses to start with `http` unless the URL is https, the body template and key are set, and the moderator
  number is valid; with `console` it still requires `ALLOW_CONSOLE_OTP=true`.
* The code expires after 5 minutes and locks after 5 wrong tries, as before.

## Example configurations (UNTESTED against the real providers: check each provider's current API documentation)
India requires sender ids and message templates to be registered with the operators (DLT) for transactional SMS; do that with your provider first.

Twilio (form body, HTTP basic auth; compute `Basic base64(AccountSid:AuthToken)` yourself and put it in `SMS_AUTH_VALUE`):
```
SMS_PROVIDER=http
SMS_URL=https://api.twilio.com/2010-04-01/Accounts/<AccountSid>/Messages.json
SMS_AUTH_HEADER=Authorization
SMS_AUTH_VALUE=Basic <base64 of AccountSid:AuthToken>
SMS_CONTENT_TYPE=form
SMS_BODY=To={to}&From={sender}&Body={message}
SMS_SENDER=<your Twilio number, URL-safe>
```
MSG91 and most Indian gateways use a template id and numbered variables instead of free text; set `SMS_BODY` to the JSON their "send OTP" or
"flow" endpoint expects, with `{to_digits}` for the mobile number, and put their auth key in `SMS_AUTH_HEADER` / `SMS_AUTH_VALUE`.

## Testing without a gateway
`scripts/` has no fake gateway, but any local HTTP listener works: point `SMS_URL` at `http://127.0.0.1:<port>/` (allowed outside prod) and
read what arrives. `HttpSmsOtpSenderTest` does exactly that.
