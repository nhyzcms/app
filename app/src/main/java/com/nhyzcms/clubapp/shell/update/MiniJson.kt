package com.nhyzcms.clubapp.shell.update

/**
 * 极简 JSON 解析器（纯 Kotlin、零依赖、可 JVM 单测）。
 *
 * 为什么自己写，而不是用 Android 自带的 org.json：
 *  1. 本项目的运行期依赖被刻意压到 4 个 androidx 库（ADR-A05），不想为一份配置文件再加库；
 *  2. Android 自带的 org.json 在 JVM 单元测试里只是空壳（调用会抛 Method not mocked），
 *     用它等于把「解析正确性」排除在 CI 之外；
 *  3. 我们只需要读一份自己维护的小 JSON，不需要序列化、不需要流式解析。
 *
 * 支持标准 JSON 子集：对象 / 数组 / 字符串 / 数字 / true / false / null。
 * 解析失败一律返回 null（调用方回退到内置默认值），**绝不抛异常**。
 *
 * 安全上限（防止被投毒的清单把壳拖死）：
 *  - 输入长度不超过 [MAX_INPUT_CHARS]；
 *  - 嵌套深度不超过 [MAX_DEPTH]。
 *
 * 返回值类型：Map（对象）、List（数组）、String、Double（数字）、Boolean、null。
 */
object MiniJson {

    /** 输入上限。真实清单只有几 KB，128 KB 已是非常宽松的上限。 */
    const val MAX_INPUT_CHARS = 128 * 1024

    /** 嵌套深度上限。正常结构不会超过 4 层。 */
    const val MAX_DEPTH = 24

    /** 解析成功返回 Map / List / String / Double / Boolean / null；任何问题都返回 null。 */
    fun parse(text: String?): Any? {
        if (text == null) return null
        if (text.isEmpty() || text.length > MAX_INPUT_CHARS) return null
        return try {
            val parser = Parser(text)
            val value = parser.readValue(0)
            parser.skipWhitespace()
            if (parser.atEnd()) value else null
        } catch (e: RuntimeException) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    private class Parser(private val src: String) {

        private var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else return
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalStateException("json-too-deep")
            skipWhitespace()
            if (atEnd()) throw IllegalStateException("json-unexpected-end")
            val c = src[pos]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> { expect("true"); true }
                c == 'f' -> { expect("false"); false }
                c == 'n' -> { expect("null"); null }
                c == '-' || (c in '0'..'9') -> readNumber()
                else -> throw IllegalStateException("json-unexpected-char")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // 跳过 '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalStateException("json-key-not-string")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw IllegalStateException("json-missing-colon")
                pos++
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalStateException("json-bad-object")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // 跳过 '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalStateException("json-bad-array")
                }
            }
        }

        private fun readString(): String {
            if (peek() != '"') throw IllegalStateException("json-not-a-string")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalStateException("json-unterminated-string")
                val c = src[pos]
                if (c == '"') {
                    pos++
                    return sb.toString()
                }
                if (c == '\\') {
                    pos++
                    if (atEnd()) throw IllegalStateException("json-bad-escape")
                    when (src[pos]) {
                        '"' -> { sb.append('"'); pos++ }
                        '\\' -> { sb.append('\\'); pos++ }
                        '/' -> { sb.append('/'); pos++ }
                        'b' -> { sb.append('\b'); pos++ }
                        'f' -> { sb.append('\u000C'); pos++ }
                        'n' -> { sb.append('\n'); pos++ }
                        'r' -> { sb.append('\r'); pos++ }
                        't' -> { sb.append('\t'); pos++ }
                        'u' -> {
                            pos++
                            sb.append(readHex4())
                        }
                        else -> throw IllegalStateException("json-bad-escape")
                    }
                } else {
                    sb.append(c)
                    pos++
                }
            }
        }

        /** 读 `\uXXXX` 里的 4 位十六进制（调用前 pos 已停在第一个十六进制位）。 */
        private fun readHex4(): Char {
            if (pos + 4 > src.length) throw IllegalStateException("json-bad-unicode")
            var value = 0
            for (i in 0 until 4) {
                val digit = Character.digit(src[pos + i], 16)
                if (digit < 0) throw IllegalStateException("json-bad-unicode")
                value = value * 16 + digit
            }
            pos += 4
            return value.toChar()
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < src.length) {
                val c = src[pos]
                val numeric = c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c in '0'..'9')
                if (!numeric) break
                pos++
            }
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: throw IllegalStateException("json-bad-number")
        }

        private fun expect(literal: String) {
            if (!src.startsWith(literal, pos)) throw IllegalStateException("json-bad-literal")
            pos += literal.length
        }

        /** 越界时返回 NUL：JSON 里不会出现裸 NUL，用它表示「到头了」。 */
        private fun peek(): Char = if (atEnd()) '\u0000' else src[pos]
    }
}
