package com.local.neckguard.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLensResolverTest {

    private fun back(
        id: String,
        focal: Float? = null,
        sensorWidth: Float? = null,
        intrinsic: Float? = null,
        minZoom: Float? = null,
        physicals: List<LensCandidate> = emptyList(),
    ) = LensCandidate(
        id = id,
        facingBack = true,
        minFocalMm = focal,
        sensorWidthMm = sensorWidth,
        intrinsicZoomRatio = intrinsic,
        minZoomRatio = minZoom,
        physicalCandidates = physicals,
    )

    private fun front(id: String) = LensCandidate(id = id, facingBack = false)

    /** 主摄：等效 f=4.7mm、传感器宽 6.4mm，约 68 度。 */
    private fun mainBack(id: String = "0", minZoom: Float? = null, physicals: List<LensCandidate> = emptyList()) =
        back(id, focal = 4.7f, sensorWidth = 6.4f, minZoom = minZoom, physicals = physicals)

    /** 超广角：f=1.7mm、传感器宽 4.6mm，约 107 度。 */
    private fun ultraWide(id: String) = back(id, focal = 1.7f, sensorWidth = 4.6f)

    @Test
    fun horizontalFov_matchesKnownValues() {
        assertEquals(68.5f, CameraLensResolver.horizontalFovDeg(4.7f, 6.4f), 0.5f)
        assertEquals(107.0f, CameraLensResolver.horizontalFovDeg(1.7f, 4.6f), 1.0f)
    }

    @Test
    fun front_and_back_returnById() {
        val candidates = listOf(mainBack(), front("1"))
        assertEquals(
            LensChoice.ById("1", LensStrategy.FRONT),
            CameraLensResolver.choose(CameraLens.FRONT, candidates, "0", "1"),
        )
        assertEquals(
            LensChoice.ById("0", LensStrategy.BACK),
            CameraLensResolver.choose(CameraLens.BACK, candidates, "0", "1"),
        )
    }

    @Test
    fun front_withoutFrontCamera_fallsBack() {
        val choice = CameraLensResolver.choose(CameraLens.FRONT, listOf(mainBack()), "0", null)
        assertTrue(choice is LensChoice.Fallback)
        assertEquals(false, (choice as LensChoice.Fallback).facingBack)
    }

    @Test
    fun ultraWide_prefersDirectCameraWithIntrinsicZoomBelowOne() {
        val candidates = listOf(mainBack(), back("2", intrinsic = 0.6f), front("1"))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, candidates, "0", "1")
        assertEquals(LensChoice.ById("2", LensStrategy.ULTRA_WIDE_DIRECT), choice)
    }

    @Test
    fun ultraWide_prefersDirectCameraByFov_whenNoIntrinsic() {
        val candidates = listOf(mainBack(), ultraWide("2"), front("1"))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, candidates, "0", "1")
        assertEquals(LensChoice.ById("2", LensStrategy.ULTRA_WIDE_DIRECT), choice)
    }

    @Test
    fun ultraWide_ignoresDirectCameraNotMeaningfullyWider() {
        // 长焦（更窄）与一个仅宽 5% 的镜头都不应被选中
        val barelyWider = back("3", focal = 4.5f, sensorWidth = 6.4f)
        val tele = back("2", focal = 12f, sensorWidth = 6.4f)
        val candidates = listOf(mainBack(minZoom = 1.0f), tele, barelyWider, front("1"))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, candidates, "0", "1")
        assertTrue("不应误选长焦或仅略宽的镜头", choice is LensChoice.Fallback)
    }

    @Test
    fun ultraWide_usesPhysicalId_whenLogicalHasWiderPhysical() {
        val logical = mainBack(physicals = listOf(back("0", focal = 4.7f, sensorWidth = 6.4f), ultraWide("3")))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, listOf(logical, front("1")), "0", "1")
        assertEquals(LensChoice.ByPhysical("0", "3"), choice)
    }

    @Test
    fun ultraWide_usesZoom_whenMinZoomBelowOne() {
        val candidates = listOf(mainBack(minZoom = 0.6f), front("1"))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, candidates, "0", "1")
        assertEquals(LensChoice.ByZoom("0", 0.6f), choice)
    }

    @Test
    fun ultraWide_ignoresZoomAtOrAboveOne() {
        val candidates = listOf(mainBack(minZoom = 1.0f), front("1"))
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, candidates, "0", "1")
        assertTrue(choice is LensChoice.Fallback)
        assertEquals("0", (choice as LensChoice.Fallback).id)
        assertTrue(choice.note.isNotBlank())
    }

    @Test
    fun ultraWide_priorityOrder_directOverPhysicalOverZoom() {
        val logical = mainBack(minZoom = 0.6f, physicals = listOf(ultraWide("3")))
        val all = listOf(logical, back("2", intrinsic = 0.5f), front("1"))
        assertEquals(
            LensChoice.ById("2", LensStrategy.ULTRA_WIDE_DIRECT),
            CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, all, "0", "1"),
        )
        // 去掉独立超广角后应落到物理镜头
        assertEquals(
            LensChoice.ByPhysical("0", "3"),
            CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, listOf(logical, front("1")), "0", "1"),
        )
        // 再去掉物理镜头才轮到变焦
        assertEquals(
            LensChoice.ByZoom("0", 0.6f),
            CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, listOf(mainBack(minZoom = 0.6f), front("1")), "0", "1"),
        )
    }

    @Test
    fun ultraWide_noBackCamera_fallsBack() {
        val choice = CameraLensResolver.choose(CameraLens.BACK_ULTRA_WIDE, listOf(front("1")), null, "1")
        assertTrue(choice is LensChoice.Fallback)
    }

    @Test
    fun lensCycle_isThreeWay() {
        assertEquals(CameraLens.BACK, CameraLens.FRONT.next())
        assertEquals(CameraLens.BACK_ULTRA_WIDE, CameraLens.BACK.next())
        assertEquals(CameraLens.FRONT, CameraLens.BACK_ULTRA_WIDE.next())
    }

    @Test
    fun parse_roundTripsAndRejectsUnknown() {
        assertEquals(CameraLens.BACK_ULTRA_WIDE, CameraLens.parse("BACK_ULTRA_WIDE"))
        assertEquals(null, CameraLens.parse("NOPE"))
        assertEquals(AnalysisResolution.R960x720, AnalysisResolution.parse("R960x720"))
        assertEquals("960x720", AnalysisResolution.R960x720.label)
        assertEquals(null, AnalysisResolution.parse(null))
    }
}
