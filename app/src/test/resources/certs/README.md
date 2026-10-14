Test-only EC P-256 keys and self-signed certificates (`CN=babyphone`), generated once with:

    openssl ecparam -name prime256v1 -genkey -noout -out NAME.pem
    openssl pkcs8 -topk8 -nocrypt -in NAME.pem -outform DER -out NAME.key.der
    openssl req -new -x509 -key NAME.pem -subj "/CN=babyphone" -days 36500 -outform DER -out NAME.der

They exist only for unit tests and are never used by the app.
