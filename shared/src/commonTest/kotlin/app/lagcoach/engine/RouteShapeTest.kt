package app.lagcoach.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteShapeTest {
    // Roughly 100 m apart along a street.
    private val a = 52.1300 to -0.4700
    private val b = 52.1309 to -0.4700
    private val c = 52.1318 to -0.4700
    private val bend = 52.1304 to -0.4706

    @Test
    fun followsPiecesBetweenStops() {
        val path = RouteShape.stitch(listOf(a, b, c), listOf(listOf(a, bend, b), listOf(b, c)))
        assertEquals(listOf(a, bend, b, c), path)
    }

    @Test
    fun usesPiecesDrawnTheOtherWay() {
        val path = RouteShape.stitch(listOf(a, b), listOf(listOf(b, bend, a)))
        assertEquals(listOf(a, bend, b), path)
    }

    @Test
    fun straightLineWhereNoPieceFits() {
        val elsewhere = 52.2000 to -0.4000
        val path = RouteShape.stitch(listOf(a, b, c), listOf(listOf(a, bend, b), listOf(b, elsewhere)))
        assertEquals(listOf(a, bend, b, c), path)
    }

    @Test
    fun ignoresOtherVariantsBranchingOff() {
        val branch = 52.1305 to -0.4600
        val path = RouteShape.stitch(listOf(a, b), listOf(listOf(a, branch), listOf(a, bend, b)))
        assertEquals(listOf(a, bend, b), path)
    }

    @Test
    fun followsShapeThatStartsAwayFromTheStop() {
        // The shape starts ~25 m up the road from one stop marker and ~80 m to the side of another.
        val nearA = 52.13022 to -0.4700
        val nearB = 52.1309 to -0.4688
        val path = RouteShape.stitch(listOf(a, b, c), listOf(listOf(nearA, bend, b), listOf(nearB, c)))
        assertEquals(listOf(nearA, bend, b, nearB, c), path)
    }

    @Test
    fun distanceToPathMeasuresAcrossSegments() {
        // Mid-way along a ~200 m straight, 50 m off to the side.
        val beside = 52.1309 to (-0.4700 + 50 / (111_320.0 * kotlin.math.cos(52.1309 * kotlin.math.PI / 180)))
        val d = RouteShape.distanceTo(listOf(a, c), beside)
        kotlin.test.assertTrue(d in 48.0..52.0, "was $d")
    }
}
