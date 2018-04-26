package tornadofx

import javafx.beans.property.*
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.event.EventHandler
import javafx.event.EventTarget
import javafx.geometry.Orientation
import javafx.geometry.Side
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.ContextMenu
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToolBar
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Gets the [Orientation] of a given positional [Side].
 */
fun Side.toOrientation()  = when(this) {
    Side.LEFT, Side.RIGHT -> Orientation.HORIZONTAL
    Side.TOP, Side.BOTTOM -> Orientation.VERTICAL
}

fun EventTarget.drawer(
    side: Side = Side.LEFT,
    multiselect: Boolean = false,
    floatingContent: Boolean = false,
    resizable: Boolean = true,
    op: Drawer.() -> Unit
) = Drawer(side, multiselect, floatingContent, resizable).attachTo(this, op)

class Drawer(side: Side, multiselect: Boolean, floatingContent: Boolean, resizable: Boolean) : BorderPane() {
    val resizableProperty: BooleanProperty = SimpleBooleanProperty(resizable)
    val resizable by resizableProperty

    val dockingOrientationProperty: ObjectProperty<Orientation> = ReadOnlyObjectWrapper(side.toOrientation())
    val dockingOrientation by dockingOrientationProperty

    val dockingSideProperty: ObjectProperty<Side> = SimpleObjectProperty(side)
    var dockingSide by dockingSideProperty

    val floatingDrawersProperty: BooleanProperty = SimpleBooleanProperty(floatingContent)
    var floatingDrawers by floatingDrawersProperty

    val maxContentSizeProperty: ObjectProperty<Number> = SimpleObjectProperty<Number>()
    var maxContentSize by maxContentSizeProperty

    val fixedContentSizeProperty: ObjectProperty<Number> = SimpleObjectProperty<Number>()
    var fixedContentSize by fixedContentSizeProperty

    val buttonArea = ToolBar().addClass(DrawerStyles.buttonArea)
    val contentArea = ExpandedDrawerContentArea()

    val items = FXCollections.observableArrayList<DrawerItem>()

    val multiselectProperty: BooleanProperty = SimpleBooleanProperty(multiselect)
    var multiselect by multiselectProperty

    val contextMenu = ContextMenu()

    override fun getUserAgentStylesheet() = DrawerStyles().base64URL.toExternalForm()

    fun item(title: String? = null, icon: Node? = null, expanded: Boolean = false, showHeader: Boolean = multiselect, op: DrawerItem.() -> Unit) =
            item(SimpleStringProperty(title), SimpleObjectProperty(icon), expanded, showHeader, op)

    fun item(title: ObservableValue<String?>, icon: ObservableValue<Node?>? = null, expanded: Boolean = multiselect, showHeader: Boolean = true, op: DrawerItem.() -> Unit): DrawerItem {
        val item = DrawerItem(this, title, icon, showHeader)
        item.button.textProperty().bind(title)
        op(item)
        items.add(item)
        if (expanded) item.button.isSelected = true
        (parent?.uiComponent<UIComponent>() as? Workspace)?.apply {
            if (root.dynamicComponentMode) {
                root.dynamicComponents.add(item)
            }
        }
        return item
    }

    fun item(uiComponent: UIComponent, expanded: Boolean = false, showHeader: Boolean = multiselect, op: DrawerItem.() -> Unit = {}): DrawerItem {
        val item = DrawerItem(this, uiComponent.titleProperty, uiComponent.iconProperty, showHeader)
        item.button.textProperty().bind(uiComponent.headingProperty)
        item.children.add(uiComponent.root)
        op(item)
        items.add(item)
        if (expanded) item.button.isSelected = true
        (parent?.uiComponent<UIComponent>() as? Workspace)?.apply {
            if (root.dynamicComponentMode) {
                root.dynamicComponents.add(item)
            }
        }
        return item
    }

    init {
        addClass(DrawerStyles.drawer)

        configureDockingSide()
        configureContextMenu()
        enforceMultiSelect()

        // Redraw if floating mode is toggled
        floatingDrawersProperty.onChange {
            updateContentArea()
            parent?.requestLayout()
            scene?.root?.requestLayout()
        }

        // Adapt docking behavior to parent
        parentProperty().onChange {
            if (it is BorderPane) {
                if (it.left == this) dockingSide = Side.LEFT
                else if (it.right == this) dockingSide = Side.RIGHT
                else if (it.bottom == this) dockingSide = Side.BOTTOM
                else if (it.top == this) dockingSide = Side.TOP
            }
        }

        // Track side property change
        dockingSideProperty.onChange { configureDockingSide() }
        dockingOrientationProperty.bind(dockingSideProperty.objectBinding {
            it?.toOrientation()
        })

        // Track button additions/removal
        items.onChange { change ->
            while (change.next()) {
                if (change.wasAdded()) {
                    change.addedSubList.asSequence().mapEach { button }.forEach {
                        configureRotation(it)
                        buttonArea+= Group(it)
                    }
                }
                if (change.wasRemoved()) {
                    change.removed.forEach {
                        val group = it.button.parent
                        it.button.removeFromParent()
                        group.removeFromParent()
                        contentArea.children.remove(it)
                    }
                }
            }
        }

        parentProperty().addListener { _, old, new ->
            old?.let { DrawerResizeEventHandler.unbind(it, this@Drawer) }
            new?.let { DrawerResizeEventHandler.bind(new, this@Drawer) }
        }
    }

    private fun enforceMultiSelect() {
        multiselectProperty.onChange {
            if (!multiselect) {
                contentArea.children.drop(1).forEach {
                    (it as DrawerItem).button.isSelected = false
                }
            }
        }
    }

    private fun configureContextMenu() {
        contextMenu.checkmenuitem("Floating drawers") {
            selectedProperty().bindBidirectional(floatingDrawersProperty)
        }
        contextMenu.checkmenuitem("Multiselect") {
            selectedProperty().bindBidirectional(multiselectProperty)
        }
        buttonArea.setOnContextMenuRequested {
            contextMenu.show(buttonArea, it.screenX, it.screenY)
        }
    }

    private fun configureRotation(button: ToggleButton) {
        button.rotate = when (dockingSide) {
            Side.LEFT -> -90.0
            Side.RIGHT -> 90.0
            else -> 0.0
        }
    }

    private fun configureDockingSide() {
        when (dockingSide) {
            Side.LEFT -> {
                left = buttonArea
                right = null
                bottom = null
                top = null
                buttonArea.orientation = Orientation.VERTICAL
            }
            Side.RIGHT -> {
                left = null
                right = buttonArea
                bottom = null
                top = null
                buttonArea.orientation = Orientation.VERTICAL
            }
            Side.BOTTOM -> {
                left = null
                right = null
                bottom = buttonArea
                top = null
                buttonArea.orientation = Orientation.HORIZONTAL
            }
            Side.TOP -> {
                left = null
                right = null
                bottom = null
                top = buttonArea
                buttonArea.orientation = Orientation.HORIZONTAL
            }
        }

        buttonArea.items.forEach {
            val button = (it as Group).children.first() as ToggleButton
            configureRotation(button)
        }
    }

    internal fun updateExpanded(item: DrawerItem) {
        if (item.expanded) {
            if (item !in contentArea.children) {
                if (!multiselect) {
                    contentArea.children.toTypedArray().forEach {
                        (it as DrawerItem).button.isSelected = false
                    }
                }
                // Insert into content area in position according to item order
                val itemIndex = items.indexOf(item)
                var inserted = false
                for (child in contentArea.children) {
                    val childIndex = items.indexOf(child)
                    if (childIndex > itemIndex) {
                        val childIndexInContentArea = contentArea.children.indexOf(child)
                        contentArea.children.add(childIndexInContentArea, item)
                        inserted = true
                        break
                    }
                }
                if (!inserted) {
                    contentArea.children.add(item)
                }
            }
        } else if (item in contentArea.children) {
            contentArea.children.remove(item)
        }

        updateContentArea()
    }

    // Dock is a child when there are expanded children
    private fun updateContentArea() {
        if (contentArea.children.isEmpty()) {
            center = null
            children.remove(contentArea)
        } else {
            if (fixedContentSize != null) {
                when (dockingSide) {
                    Side.LEFT, Side.RIGHT -> {
                        contentArea.maxWidth = fixedContentSize.toDouble()
                        contentArea.minWidth = fixedContentSize.toDouble()
                    }
                    Side.TOP, Side.BOTTOM -> {
                        contentArea.maxHeight = fixedContentSize.toDouble()
                        contentArea.minHeight = fixedContentSize.toDouble()
                    }
                }
            } else {
                contentArea.maxWidth = USE_COMPUTED_SIZE
                contentArea.minWidth = USE_COMPUTED_SIZE
                contentArea.maxHeight = USE_COMPUTED_SIZE
                contentArea.minHeight = USE_COMPUTED_SIZE
                if (maxContentSize != null) {
                    when (dockingSide) {
                        Side.LEFT, Side.RIGHT -> contentArea.maxWidth = maxContentSize.toDouble()
                        Side.TOP, Side.BOTTOM -> contentArea.maxHeight = maxContentSize.toDouble()
                    }
                }
            }

            if (floatingDrawers) {
                contentArea.isManaged = false
                if (contentArea !in children) children.add(contentArea)
            } else {
                contentArea.isManaged = true
                if (contentArea in children) children.remove(contentArea)
                center = contentArea
            }
        }
    }

    override fun layoutChildren() {
        super.layoutChildren()
        if (floatingDrawers && contentArea.children.isNotEmpty()) {
            val buttonBounds = buttonArea.layoutBounds
            contentArea.resizeRelocate(buttonBounds.maxX, buttonBounds.minY, contentArea.prefWidth(-1.0), buttonBounds.height)
        }
    }
}

class ExpandedDrawerContentArea : VBox() {
    init {
        addClass(DrawerStyles.contentArea)
        children.onChange { change ->
            while (change.next()) {
                if (change.wasAdded()) {
                    change.addedSubList.asSequence()
                            .filter { VBox.getVgrow(it) == null }
                            .forEach { VBox.setVgrow(it, Priority.ALWAYS) }
                }
            }
        }
    }

}

class DrawerItem(val drawer: Drawer, title: ObservableValue<String?>? = null, icon: ObservableValue<Node?>? = null, showHeader: Boolean) : VBox() {
    internal val button = ToggleButton().apply {
        if (title != null) textProperty().bind(title)
        if (icon != null) graphicProperty().bind(icon)
    }

    val expandedProperty = button.selectedProperty()
    var expanded by expandedProperty

    init {
        addClass(DrawerStyles.drawerItem)
        if (showHeader) {
            titledpane {
                textProperty().bind(title)
                isCollapsible = false
            }
        }
        button.selectedProperty().onChange { drawer.updateExpanded(this) }
        drawer.updateExpanded(this)

        children.onChange { change ->
            while (change.next()) {
                if (change.wasAdded()) {
                    change.addedSubList.asSequence()
                            .filter { VBox.getVgrow(it) == null }
                            .forEach { VBox.setVgrow(it, Priority.ALWAYS) }
                }
            }
        }
    }
}

class DrawerStyles : Stylesheet() {
    companion object {
        val drawer by cssclass()
        val drawerItem by cssclass()
        val buttonArea by cssclass()
        val contentArea by cssclass()
    }

    init {
        drawer {
            contentArea {
                borderColor += box(Color.DARKGRAY)
                borderWidth += box(0.5.px)
            }
            buttonArea {
                spacing = 0.px
                padding = box(0.px)
                toggleButton {
                    backgroundInsets += box(0.px)
                    backgroundRadius += box(0.px)
                    and(selected) {
                        backgroundColor += c("#818181")
                        textFill = Color.WHITE
                    }
                }
            }
        }
        drawerItem child titledPane {
            title {
                backgroundRadius += box(0.px)
                padding = box(2.px, 5.px)
            }
            content {
                borderColor += box(Color.TRANSPARENT)
            }
        }
    }
}

// @todo - move drawerspecific logic (i.e. inResizeBounds, isResizable) to `Drawer`
// now that the listeners are per-parent?
class DrawerResizeEventHandler(private val parent: Parent) : EventHandler<MouseEvent> {

    internal var dragTarget: Drawer? = null
    internal val drawers = mutableListOf<Drawer>()

    /**
     * Create a 1D line centered on the given [point] with the specified [length].
     */
    private fun centeredLine(point: Double, length: Double) = (point - length / 2.0)..(point + length / 2.0)

    /**
     * Checks if the layout coordinates given by [x] and [y] are within bounds of dragging the drawers
     * border to resize it.
     */
    private fun inResizeBounds(drawer: Drawer, x: Double, y: Double): Boolean {
        //@todo - should/can we get this from the border properties?
        val resizeAnchorSize = MINIMUM_RESIZE_ANCHOR_WIDTH.value

        val width = drawer.width
        val height = drawer.height
        val layoutX = drawer.layoutX
        val layoutY = drawer.layoutY

        val horizontalBounds = when (drawer.dockingSide) {
            Side.LEFT -> centeredLine(layoutX + width, resizeAnchorSize)
            Side.RIGHT -> centeredLine(layoutX, resizeAnchorSize)
            else -> layoutX..layoutX + width
        }

        val verticalBounds = when (drawer.dockingSide) {
            Side.TOP -> centeredLine(layoutY + height, resizeAnchorSize)
            Side.BOTTOM -> centeredLine(layoutY, resizeAnchorSize)
            else -> layoutY..layoutY + height
        }

        return x in horizontalBounds && y in verticalBounds
    }

    /**
     * Checks if the target [drawer]'s isResizable property is set and if it currently has any
     * of its content areas expanded.
     */
    private fun isResizable(drawer: Drawer) = drawer.resizable && drawer.items.any { it.expanded }

    /**
     * Gets the _resize_ cursor for the [drawer]s current [Orientation].
     */
    private fun resizeCursor(drawer: Drawer) = when (drawer.dockingOrientation) {
        Orientation.HORIZONTAL -> Cursor.H_RESIZE
        Orientation.VERTICAL -> Cursor.V_RESIZE
        else -> Cursor.DEFAULT
    }

    /**
     * Handle mouse move events and set the cursor to the appropriate _resize_ cursor
     * when hovering over a [Drawer]s resizable bounds.
     */
    private fun handleMouseOver(event: MouseEvent) {
        val target = drawers.firstOrNull { isResizable(it) && inResizeBounds(it, event.x, event.y) }
        if (target != null) {
            event.consume()
            parent.cursor = resizeCursor(target)
        } else {
            parent.cursor = Cursor.DEFAULT
        }
    }

    /**
     * Handle a mouse drag event and update the drag target, if any, to use a new
     * preferred height and width depending on the [Orientation] of the [Drawer].
     */
    private fun handleResize(event: MouseEvent) {
        dragTarget?.let { drawer ->
            event.consume()
            val side = drawer.dockingSide
            val newWidth = event.x - drawer.layoutX - if (side == Side.RIGHT) drawer.width else 0.0
            val newHeight = event.y - drawer.layoutY - if (side == Side.BOTTOM) drawer.height else 0.0

            when (drawer.dockingOrientation) {
                Orientation.HORIZONTAL -> drawer.prefWidth = max(0.0, abs(newWidth))
                Orientation.VERTICAL -> drawer.prefHeight = max(0.0, abs(newHeight))
            }
        }
    }

    /**
     * Handle a mouse press and check if there are any [Drawer]s with resizable bounds
     * on the mouse click position.
     */
    private fun handleResizeStart(event: MouseEvent) {
        val target = drawers.firstOrNull { isResizable(it) && inResizeBounds(it, event.x, event.y) }

        if (target != null) {
            event.consume()
            dragTarget = target
        }
    }

    /**
     * Handle the release of a mouse button and reset the cursor to the default
     * and set the [dragTarget] to `null`.
     */
    private fun handleResizeEnd(event: MouseEvent) {
        dragTarget?.let {
            event.consume()
            parent.cursor = Cursor.DEFAULT
        }

        dragTarget = null
    }

    override fun handle(event: MouseEvent) {
        when (event.eventType) {
            MouseEvent.MOUSE_DRAGGED -> handleResize(event)
            MouseEvent.MOUSE_MOVED -> handleMouseOver(event)
            MouseEvent.MOUSE_PRESSED -> handleResizeStart(event)
            MouseEvent.MOUSE_RELEASED -> handleResizeEnd(event)
        }
    }

    companion object {

        /**
         * A set of [MouseEvent] types that are listened on to resize [Drawer]s.
         */
        private val RESIZE_EVENT_TYPES = setOf(
            MouseEvent.MOUSE_DRAGGED,
            MouseEvent.MOUSE_MOVED,
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED
        )

        /**
         * The minimum size of the drag anchor on the border of a [Drawer].
         */
        private val MINIMUM_RESIZE_ANCHOR_WIDTH = 5.px

        /**
         * Bind a [DrawerResizeEventHandler] to the [parent], if one is not already attached via properties, and add
         * the [drawer] to it's list of managed drawers.
         */
        fun bind(parent: Parent, drawer: Drawer) {
            val listener = parent.properties.computeIfAbsent(DrawerResizeEventHandler::class) {
                DrawerResizeEventHandler(parent).also {
                    for (eventType in RESIZE_EVENT_TYPES) {
                        parent.addEventFilter(eventType, it)
                    }
                }
            } as DrawerResizeEventHandler

            listener.drawers.add(drawer)
        }

        /**
         * Remove the given [Drawer] from it's old parents list of managed drawers if a resize
         * event handler exists on it.
         */
        fun unbind(oldParent: Parent, drawer: Drawer) {
            val listener = oldParent.properties[DrawerResizeEventHandler::class] as DrawerResizeEventHandler? ?: return
            listener.drawers.remove(drawer)
        }

    }
}