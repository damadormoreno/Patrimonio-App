package com.denebapps.patrimonio.ui.theme

import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.ui.components.PORTFOLIO_LOOK
import com.denebapps.patrimonio.ui.components.typeColor
import com.denebapps.patrimonio.ui.screens.portfolio.assetClassColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colors of the stacked bars must tell their segments apart for the common color-vision deficiencies, in both
 * themes. The bars are sorted by value, so any two classes can end up side by side: every pair is checked.
 *
 * Distance is CIEDE2000 after simulating each vision (see [Vision]); [MIN_CLASS_DISTANCE] is the smallest accepted
 * (about 5 times a just-noticeable difference, enough to tell two flat areas apart without a legend); the Cartera
 * share, which stands in a bar with account types of any color, needs [MIN_PORTFOLIO_DISTANCE] from each of them.
 */
class ChartPaletteAccessibilityTest {
    private val themes = mapOf("light" to LightColors, "dark" to DarkColors)
    private val classes: List<AssetClass?> = AssetClass.entries + null

    @Test
    fun `the distance measure matches the CIEDE2000 reference pairs`() {
        // Sharma, Wu and Dalal (2005), supplementary test data, pairs 1 and 7.
        assertEquals(2.0425, deltaE2000(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485)), 1e-4)
        assertEquals(27.1492, deltaE2000(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0)), 1e-4)
    }

    @Test
    fun `every asset class stays distinguishable from every other under each vision`() {
        val failures = buildList {
            themes.forEach { (themeName, colors) ->
                Vision.entries.forEach { vision ->
                    classes.forEachIndexed { index, first ->
                        classes.drop(index + 1).forEach { second ->
                            val distance =
                                distanceAs(colors.assetClassColor(first), colors.assetClassColor(second), vision)
                            if (distance < MIN_CLASS_DISTANCE) {
                                add("$themeName/${vision.label}: $first vs $second = ${distance.format()}")
                            }
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "classes too close (min $MIN_CLASS_DISTANCE):\n" + failures.joinToString("\n"))
    }

    @Test
    fun `an asset class never melts into the card it is drawn on`() {
        val failures = buildList {
            themes.forEach { (themeName, colors) ->
                classes.forEach { assetClass ->
                    val distance = distanceAs(colors.assetClassColor(assetClass), colors.surface, Vision.NORMAL)
                    if (distance < MIN_SURFACE_DISTANCE) add("$themeName: $assetClass = ${distance.format()}")
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "classes too close to the surface (min $MIN_SURFACE_DISTANCE):\n" + failures.joinToString("\n"),
        )
    }

    @Test
    fun `the Cartera share differs from every account type color under each vision`() {
        val failures = buildList {
            themes.forEach { (themeName, colors) ->
                val portfolio = colors.typeColor(PORTFOLIO_LOOK)
                Vision.entries.forEach { vision ->
                    TypeColor.entries.forEach { accountColor ->
                        val distance = distanceAs(portfolio, colors.typeColor(accountColor), vision)
                        if (distance < MIN_PORTFOLIO_DISTANCE) {
                            add("$themeName/${vision.label}: Cartera vs $accountColor = ${distance.format()}")
                        }
                    }
                }
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Cartera too close (min $MIN_PORTFOLIO_DISTANCE):\n" + failures.joinToString("\n"),
        )
    }

    private fun Double.format(): String = (kotlin.math.round(this * 10) / 10).toString()

    private companion object {
        const val MIN_CLASS_DISTANCE = 10.0
        const val MIN_SURFACE_DISTANCE = 20.0
        const val MIN_PORTFOLIO_DISTANCE = 12.0
    }
}
