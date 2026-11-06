package ru.tapps.trackerblocker.stable.tunnel

/**
 * Классы-модели для JNI-моста userspace-стека.
 *
 * Имена полей и их типы жёстко зафиксированы в нативном коде NetGuard
 * (`netguard.c`): он ищет их через `GetFieldID` по точному имени и подписи.
 * Поэтому здесь нельзя переименовывать поля или менять типы — только
 * добавлять новые.
 *
 * Файл распространяется под GPLv3, как и весь проект. Нативный стек взят из
 * NetGuard (Copyright 2015-2026 Marcel Bokhorst, M66B) и адаптирован под
 * AntiTrack.
 */

/** Пакет, по которому нужно принять решение: пропустить или заблокировать. */
class Packet {
    @JvmField var time: Long = 0
    @JvmField var version: Int = 0
    @JvmField var protocol: Int = 0
    @JvmField var flags: String? = null
    @JvmField var saddr: String? = null
    @JvmField var sport: Int = 0
    @JvmField var daddr: String? = null
    @JvmField var dport: Int = 0
    @JvmField var data: String? = null
    @JvmField var uid: Int = 0
    @JvmField var allowed: Boolean = false

    override fun toString(): String =
        "uid=$uid v$version p$protocol $daddr/$dport"
}

/**
 * Результат разрешения пакета. `null` из `isAddressAllowed` означает блокировку,
 * непустой объект — пропуск. Поля `raddr`/`rport` нужны только для
 * перенаправления (в AntiTrack не используются).
 */
class Allowed {
    @JvmField var raddr: String? = null
    @JvmField var rport: Int = 0

    constructor()

    constructor(raddr: String?, rport: Int) {
        this.raddr = raddr
        this.rport = rport
    }
}

/**
 * DNS-ответ, который нативный стек разобрал самостоятельно. Используется для
 * построения таблицы «IP → домен»: по ней потом определяется трекер для
 * конкретного соединения.
 */
class ResourceRecord {
    @JvmField var Time: Long = 0
    @JvmField var QName: String? = null
    @JvmField var AName: String? = null
    @JvmField var Resource: String? = null
    @JvmField var TTL: Int = 0
    @JvmField var uid: Int = 0
}

/** Аккаунтинг трафика (в AntiTrack не используется, но JNI его ожидает). */
class Usage {
    @JvmField var Time: Long = 0
    @JvmField var Version: Int = 0
    @JvmField var Protocol: Int = 0
    @JvmField var DAddr: String? = null
    @JvmField var DPort: Int = 0
    @JvmField var Uid: Int = 0
    @JvmField var Sent: Long = 0
    @JvmField var Received: Long = 0
}
