package com.liveaireply.app.conversation

/**
 * Builders used across the test suite to describe accessibility trees in a readable
 * way. They produce the same [NodeView] shape the Android NodeMapper produces.
 */
object TestFixtures {

    const val SCREEN_W = 1080
    const val SCREEN_H = 2400

    fun rect(left: Int, top: Int, right: Int, bottom: Int) = RectView(left, top, right, bottom)

    fun textNode(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        viewId: String? = null,
        contentDescription: String? = null,
        editable: Boolean = false,
        password: Boolean = false,
        className: String = "android.widget.TextView",
        children: List<NodeView> = emptyList()
    ) = NodeView(
        text = text,
        contentDescription = contentDescription,
        className = className,
        viewIdResourceName = viewId,
        packageName = "com.example.chat",
        isEditable = editable,
        isPassword = password,
        isVisibleToUser = true,
        bounds = rect(left, top, right, bottom),
        children = children
    )

    /** Bubble on the left half of the screen (an incoming message). */
    fun incoming(text: String, top: Int, viewId: String? = null) =
        textNode(text, 24, top, 520, top + 90, viewId = viewId)

    /** Bubble on the right half of the screen (an outgoing message). */
    fun outgoing(text: String, top: Int, viewId: String? = null) =
        textNode(text, 560, top, 1056, top + 90, viewId = viewId)

    fun composer(text: String = "", viewId: String = "com.example.chat:id/message_input") =
        textNode(
            text = text,
            left = 24,
            top = 2240,
            right = 900,
            bottom = 2340,
            viewId = viewId,
            editable = true,
            className = "android.widget.EditText"
        )

    fun sendButton(viewId: String? = "com.example.chat:id/send", description: String = "Send") =
        NodeView(
            text = null,
            contentDescription = description,
            className = "android.widget.ImageButton",
            viewIdResourceName = viewId,
            packageName = "com.example.chat",
            isClickable = true,
            isVisibleToUser = true,
            isEnabled = true,
            bounds = rect(930, 2250, 1050, 2330)
        )

    fun screen(
        children: List<NodeView>,
        packageName: String = "com.example.chat",
        activity: String? = "com.example.chat.ConversationActivity"
    ) = NodeView(
        text = null,
        contentDescription = null,
        className = "android.widget.FrameLayout",
        viewIdResourceName = null,
        packageName = packageName,
        isVisibleToUser = true,
        bounds = rect(0, 0, SCREEN_W, SCREEN_H),
        children = children
    )

    fun turn(
        text: String,
        direction: TurnDirection,
        confidence: Float = 1f,
        source: TurnSource = TurnSource.ACCESSIBILITY
    ) = ChatTurn(
        text = text,
        direction = direction,
        source = source,
        capturedAtMs = 1_000L,
        confidence = confidence
    )

    fun snapshot(
        vararg turns: ChatTurn,
        packageName: String = "com.example.chat",
        screenLabel: String? = "Hellen",
        capturedAtMs: Long = 1_000L,
        source: TurnSource = TurnSource.ACCESSIBILITY,
        extractionConfidence: Float = 1f
    ) = ConversationSnapshot(
        packageName = packageName,
        activityName = "ChatActivity",
        screenLabel = screenLabel,
        turns = turns.toList(),
        capturedAtMs = capturedAtMs,
        hasEditableInput = true,
        editableFieldSignature = "com.example.chat:id/message_input",
        screenWidth = SCREEN_W,
        screenHeight = SCREEN_H,
        source = source,
        extractionConfidence = extractionConfidence
    )
}
