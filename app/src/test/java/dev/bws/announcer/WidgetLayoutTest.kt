package dev.bws.announcer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home-screen widgets are drawn by the launcher through RemoteViews, which only inflates a fixed
 * allow-list of view classes. Anything else (even a plain <View>) makes the widget fail to load.
 */
class WidgetLayoutTest {
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
        "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView", "ProgressBar", "TextView",
        "ViewFlipper", "ListView", "GridView", "StackView", "AdapterViewFlipper", "TextClock",
        "CheckBox", "Switch", "RadioButton", "RadioGroup", "ViewStub", "merge", "include",
    )

    @Test fun widgetLayoutsUseOnlyRemoteViewsClasses() {
        val layouts = listOf(File("src/main/res/layout/widget.xml"), File("src/main/res/layout/widget_compact.xml"))
        for (f in layouts) {
            assertTrue("missing ${f.path}", f.exists())
            val tags = Regex("""<([A-Za-z][\w.]*)[\s>/]""").findAll(f.readText()).map { it.groupValues[1] }.toList()
            val bad = tags.filter { it.substringAfterLast('.') !in allowed }
            assertTrue("${f.name} uses views RemoteViews can't inflate: $bad", bad.isEmpty())
        }
    }
}
