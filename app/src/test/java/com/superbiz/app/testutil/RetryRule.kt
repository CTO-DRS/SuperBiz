package com.superbiz.app.testutil

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * [FLAKE_LEDGER #3 — احتواء موثق، لا إعادة تشغيل صامتة] يعيد محاولة الاختبار حتى [attempts] مرات
 * ويطبع كل فشل وسيط صراحةً في السجل (فلا يُخفى التذبذب)، ويصفّر مفردات DataStore قبل كل محاولة
 * لأن حالتها هي ما يتعطل بين اختبارات Robolectric. الفشل في كل المحاولات يبقى فشلًا حقيقيًا.
 */
class RetryRule(private val attempts: Int = 3) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            var last: Throwable? = null
            for (i in 1..attempts) {
                try {
                    DataStoreTestReset.reset()
                    base.evaluate()
                    if (i > 1) System.err.println("FLAKE-RETRY: ${description.displayName} passed on attempt $i")
                    return
                } catch (t: Throwable) {
                    last = t
                    System.err.println("FLAKE-RETRY: ${description.displayName} failed attempt $i/$attempts: ${t.javaClass.simpleName}")
                }
            }
            throw last!!
        }
    }
}
