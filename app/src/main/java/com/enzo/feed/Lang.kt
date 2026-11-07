package com.enzo.feed

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Idioma do app: português ou inglês. Sem escolha, segue o idioma do celular (inglês se for inglês, senão português). */
object Lang {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun code(ctx: Context): String =
        sp(ctx).getString("lang", null) ?: if (Locale.getDefault().language == "en") "en" else "pt"

    fun set(ctx: Context, code: String) {
        sp(ctx).edit().putString("lang", code).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    /** Garante que a tela abra no idioma escolhido (inclusive em Androids antigos). */
    fun ensure(ctx: Context) {
        val pref = sp(ctx).getString("lang", null) ?: return
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!cur.startsWith(pref)) set(ctx, pref)
    }

    /** Contexto no idioma escolhido, para textos fora das telas (notificações). */
    fun localized(base: Context): Context {
        val c = Configuration(base.resources.configuration)
        c.setLocale(Locale(code(base)))
        return base.createConfigurationContext(c)
    }
}
