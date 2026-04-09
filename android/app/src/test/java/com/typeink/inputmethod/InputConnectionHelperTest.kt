package com.typeink.inputmethod

import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class InputConnectionHelperTest {

    @Test
    fun `deleteSurroundingText deletes current selection first`() {
        val recorder = RecordingInputConnection(selectedText = "整段选中文本")
        val helper = InputConnectionHelper("TestInputConnection")

        val result = helper.deleteSurroundingText(recorder.asInputConnection(), 1, 0)

        assertTrue(result)
        assertEquals("", recorder.committedText)
        assertNull(recorder.deletedBefore)
        assertNull(recorder.deletedAfter)
    }

    @Test
    fun `deleteSurroundingText falls back to surrounding delete when no selection`() {
        val recorder = RecordingInputConnection(selectedText = null)
        val helper = InputConnectionHelper("TestInputConnection")

        val result = helper.deleteSurroundingText(recorder.asInputConnection(), 1, 0)

        assertTrue(result)
        assertNull(recorder.committedText)
        assertEquals(1, recorder.deletedBefore)
        assertEquals(0, recorder.deletedAfter)
    }

    private class RecordingInputConnection(
        private val selectedText: CharSequence?,
    ) : InvocationHandler {
        var committedText: CharSequence? = null
        var deletedBefore: Int? = null
        var deletedAfter: Int? = null

        fun asInputConnection(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
                this,
            ) as InputConnection
        }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "finishComposingText" -> true
                "getSelectedText" -> selectedText
                "commitText" -> {
                    committedText = args?.get(0) as? CharSequence
                    true
                }

                "deleteSurroundingText" -> {
                    deletedBefore = args?.get(0) as? Int
                    deletedAfter = args?.get(1) as? Int
                    true
                }

                "toString" -> "RecordingInputConnection"
                else -> defaultValue(method.returnType)
            }
        }

        private fun defaultValue(returnType: Class<*>): Any? {
            return when {
                returnType == Boolean::class.javaPrimitiveType -> false
                returnType == Int::class.javaPrimitiveType -> 0
                returnType == Long::class.javaPrimitiveType -> 0L
                returnType == Float::class.javaPrimitiveType -> 0f
                returnType == Double::class.javaPrimitiveType -> 0.0
                returnType == Char::class.javaPrimitiveType -> '\u0000'
                returnType == Short::class.javaPrimitiveType -> 0.toShort()
                returnType == Byte::class.javaPrimitiveType -> 0.toByte()
                else -> null
            }
        }
    }
}
