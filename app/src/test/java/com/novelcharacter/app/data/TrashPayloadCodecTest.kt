package com.novelcharacter.app.data

import com.novelcharacter.app.data.repository.TrashPayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 payload의 저장 형식 — GZIP+Base64 인라인 압축 (B-17).
 *
 * **이 시험이 지키는 것은 압축률이 아니라 '읽기 둘'이다.** 형식을 바꾸는 변경이라 R-2가
 * 요구하는 것은 *이미 쌓여 있는 평문 payload가 그대로 읽히는가*이고, 그것이 깨지면
 * 마이그레이션 없이 병합된 이 판이 **옛 백업을 통째로 못 읽게 만든다.**
 */
class TrashPayloadCodecTest {

    /** 실제 payload를 닮은 값 — 반복이 많은 JSON이라야 압축 갈래를 실제로 탄다. */
    private fun bigJson(): String {
        val one = """{"id":%d,"name":"가온","fieldValues":[{"k":"머리카락","v":"검정"}]}"""
        return (1..200).joinToString(",", prefix = "[", postfix = "]") { one.format(it) }
    }

    @Test
    fun `압축한 payload가 원문 그대로 되돌아온다`() {
        val plain = bigJson()
        val stored = TrashPayloadCodec.encode(plain)
        assertTrue("반복 많은 JSON은 압축이 이득이라 압축 형식이어야 한다",
            TrashPayloadCodec.isCompressed(stored))
        assertTrue("압축분이 원문보다 짧아야 저장할 값이 있다", stored.length < plain.length)
        assertEquals(plain, TrashPayloadCodec.decode(stored))
    }

    @Test
    fun `구버전 평문 payload는 표식이 없어 그대로 읽힌다`() {
        // 이미 DB에 쌓여 있는 행의 모양 — 마이그레이션 없이 그대로 읽혀야 한다(R-2).
        val legacy = """{"character":{"id":7,"name":"가온"},"schemaVersion":3}"""
        assertFalse(TrashPayloadCodec.isCompressed(legacy))
        assertEquals(legacy, TrashPayloadCodec.decode(legacy))
    }

    @Test
    fun `압축이 이득이 아니면 평문으로 쓴다`() {
        // 작은 payload는 gzip 머리말과 Base64의 4-3 팽창 때문에 되레 커진다.
        val small = """{"id":1}"""
        val stored = TrashPayloadCodec.encode(small)
        assertEquals("이득이 없으면 원문 그대로여야 한다", small, stored)
        assertFalse(TrashPayloadCodec.isCompressed(stored))
        // **곁다리가 본론이다** — 평문이 계속 *쓰이는* 형식으로 남아야 폴백 경로가 죽은 코드가
        // 되지 않는다. 죽은 코드가 되면 다음 사람이 "이제 안 쓰니 지우자"로 지운다.
        assertEquals(small, TrashPayloadCodec.decode(stored))
    }

    @Test
    fun `표식은 평문 JSON과 겹칠 수 없다`() {
        // payload는 Gson이 만든 JSON이라 언제나 { 또는 [ 로 시작하고, 표식은 영문자로 시작한다.
        // 겹치면 평문을 압축분으로 오인해 **복원이 그 스냅샷에서만 조용히 실패한다.**
        assertFalse(TrashPayloadCodec.MARKER.startsWith("{"))
        assertFalse(TrashPayloadCodec.MARKER.startsWith("["))
        assertFalse(TrashPayloadCodec.isCompressed("""{"a":1}"""))
        assertFalse(TrashPayloadCodec.isCompressed("""[{"a":1}]"""))
    }

    @Test
    fun `유니코드가 왕복에서 상하지 않는다`() {
        // UTF-8로 감아 Base64로 싣는 경로라, 인코딩을 한 자리에서만 틀려도 한글이 깨진다.
        val plain = """{"name":"김하늘","memo":"긴 설명 — 이모지 🌙 포함"}""".repeat(50)
        assertEquals(plain, TrashPayloadCodec.decode(TrashPayloadCodec.encode(plain)))
    }

    @Test
    fun `깨진 압축 값은 조용히 넘어가지 않고 던진다`() {
        // 여기서 빈 문자열이나 원문을 돌려주면 Gson이 조용히 null을 내고, 복원은
        // *유실이 없다고 말하면서* 빈 껍데기를 되살린다 — 부르는 쪽이 실패로 처분해야 한다.
        val broken = TrashPayloadCodec.MARKER + "이건Base64가아니다!!"
        var threw = false
        try {
            TrashPayloadCodec.decode(broken)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("깨진 압축분은 예외로 드러나야 한다", threw)
    }

    @Test
    fun `빈 문자열도 왕복한다`() {
        assertEquals("", TrashPayloadCodec.decode(TrashPayloadCodec.encode("")))
    }
}
