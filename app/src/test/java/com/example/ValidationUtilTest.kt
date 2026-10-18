package com.example

import com.example.data.util.ValidationUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationUtilTest {

    @Test
    fun nationalCode_acceptsValidCodes() {
        // Well-known valid sample codes
        assertTrue(ValidationUtil.isValidNationalCode("0012345679"))
        assertTrue(ValidationUtil.isValidNationalCode("0499370899"))
    }

    @Test
    fun nationalCode_rejectsInvalid() {
        assertFalse(ValidationUtil.isValidNationalCode("1234567890"))
        assertFalse(ValidationUtil.isValidNationalCode("0000000000"))
        assertFalse(ValidationUtil.isValidNationalCode("1111111111"))
        assertFalse(ValidationUtil.isValidNationalCode("12345"))
        assertFalse(ValidationUtil.isValidNationalCode(""))
    }

    @Test
    fun nationalCode_acceptsPersianDigits() {
        assertTrue(ValidationUtil.isValidNationalCode("۰۰۱۲۳۴۵۶۷۹"))
    }

    @Test
    fun mobile_acceptsValidFormats() {
        assertTrue(ValidationUtil.isValidMobile("09121234567"))
        assertTrue(ValidationUtil.isValidMobile("+989121234567"))
        assertTrue(ValidationUtil.isValidMobile("9121234567"))
        assertTrue(ValidationUtil.isValidMobile("۰۹۱۲۱۲۳۴۵۶۷"))
    }

    @Test
    fun mobile_rejectsInvalid() {
        assertFalse(ValidationUtil.isValidMobile("02188889999"))
        assertFalse(ValidationUtil.isValidMobile("0912123456"))
        assertFalse(ValidationUtil.isValidMobile(""))
    }

    @Test
    fun phone_normalizes() {
        assertEquals("09121234567", ValidationUtil.normalizePhone("۰۹۱۲ ۱۲۳ ۴۵۶۷"))
        assertEquals("+989121234567", ValidationUtil.normalizePhone("+98 912 123 4567"))
    }
}
