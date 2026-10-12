(ns tech.jgood.gleanmo.crypto
  "Symmetric encryption for credentials stored in XTDB: AES-256-GCM over
   `javax.crypto`, with a random 12-byte nonce per encryption.

   The key is `:gleanmo/credentials-key` (`CREDENTIALS_KEY`, base64, 32
   bytes). A leaked database or backup then exposes only ciphertext; reading a
   secret needs the database and the environment together.

   `aad` (associated data) is authenticated but not encrypted. Callers pass
   the owning user's id, so a ciphertext copied onto another user's document
   fails to decrypt.

   Key rotation is not handled yet — see roadmap/056-cronometer-integration.md,
   Open Questions. `key-version` is stored so it can be."
  (:import
   [java.nio.charset StandardCharsets]
   [java.security SecureRandom]
   [java.util Base64]
   [javax.crypto Cipher]
   [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def key-version
  "The version of the key `encrypt` uses."
  1)

(def ^:private nonce-bytes 12)
(def ^:private tag-bits 128)

(defonce ^:private random (SecureRandom.))

(defn- b64->bytes
  ^bytes [^String s]
  (.decode (Base64/getDecoder) s))

(defn- bytes->b64
  [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn parse-key
  "The AES key for a base64 32-byte string, or nil when `s` is blank or not
   one. Never throws, so a bad key reads as \"not configured\"."
  [s]
  (when (and (string? s) (seq s))
    (try
      (let [b (b64->bytes s)]
        (when (= 32 (alength b))
          (SecretKeySpec. b "AES")))
      (catch IllegalArgumentException _ nil))))

(defn generate-key
  "A new random base64 32-byte key, for `CREDENTIALS_KEY`."
  []
  (let [b (byte-array 32)]
    (.nextBytes ^SecureRandom random b)
    (bytes->b64 b)))

(defn- cipher
  ^Cipher [mode ^SecretKeySpec k ^bytes nonce ^String aad]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode) k (GCMParameterSpec. tag-bits nonce))
    (.updateAAD (.getBytes aad StandardCharsets/UTF_8))))

(defn encrypt
  "Encrypt `plaintext` under key `k` (from `parse-key`).
   Returns `{:ciphertext b64 :nonce b64 :key-version n}`."
  [k ^String plaintext ^String aad]
  (let [nonce (byte-array nonce-bytes)]
    (.nextBytes ^SecureRandom random nonce)
    {:ciphertext  (bytes->b64
                   (.doFinal (cipher Cipher/ENCRYPT_MODE k nonce aad)
                             (.getBytes plaintext StandardCharsets/UTF_8)))
     :nonce       (bytes->b64 nonce)
     :key-version key-version}))

(defn decrypt
  "The plaintext for an `encrypt` result. Throws when the key, nonce, `aad` or
   ciphertext do not match."
  [k {:keys [ciphertext nonce]} ^String aad]
  (String. (.doFinal (cipher Cipher/DECRYPT_MODE k (b64->bytes nonce) aad)
                     (b64->bytes ciphertext))
           StandardCharsets/UTF_8))
