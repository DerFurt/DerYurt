package com.derfurt.deryurt.core

/**
 * Bir giriş denemesinin sonucu. Loglarda ve kayıtlarda sadece bu enum'un adı geçer;
 * kimlik bilgisi veya sunucu cevabı asla loglanmaz.
 */
enum class LoginOutcome {
    /** generate_204 zaten 204 döndü, giriş gerekmedi. */
    ALREADY_ONLINE,

    /** POST sonrası generate_204 204 döndü. */
    SUCCESS,

    /** POST gitti ama internet açılmadı; büyük ihtimalle kullanıcı adı/şifre hatalı. */
    WRONG_CREDENTIALS,

    /** Portal captcha istiyor. Otomatik geçmeye çalışılmaz, kullanıcıya haber verilir. */
    CAPTCHA,

    /** Hesap başka bir cihazda açık; portal önce oradan çıkış istiyor. Yanlış şifre sayılmaz. */
    OTHER_DEVICE,

    /** Bu wifi GSB değil (wifi.gsb.gov.tr çözülemedi ya da sertifikası doğrulanamadı). Kimlik bilgisi gönderilmedi. */
    NOT_GSB,

    /** Portal zaman aşımına uğradı. */
    TIMEOUT,

    /** Portal hata kodu döndü veya bağlantı koptu. */
    PORTAL_ERROR,

    /** Bağlı wifi yok. */
    NO_WIFI,

    /** Kayıtlı kimlik bilgisi yok. */
    NO_CREDENTIALS,

    /** Deneme limiti / captcha kilidi nedeniyle otomatik deneme yapılmadı. */
    SKIPPED_LOCKED,

    /** Portal uzun süredir yanıt vermediği için 1 saatlik beklemedeyiz; deneme yapılmadı. */
    SKIPPED_BACKOFF,
}
