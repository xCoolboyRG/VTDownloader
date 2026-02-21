package me.bymartrixx.vtd.gui.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.widget.button.ButtonWidget;
import net.minecraft.text.Text;

public class MutableMessageButtonWidget extends ButtonWidget {
    private final Text defaultMessage;
    private Text currentMessage;

    public MutableMessageButtonWidget(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION);
        this.defaultMessage = message;
        this.currentMessage = message;
    }

    public void resetMessage() {
        this.currentMessage = this.defaultMessage;
    }

    public void setMessage(Text message) {
        this.currentMessage = message;
    }

    @Override
    public Text getMessage() {
        return this.currentMessage;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderWidget(graphics, mouseX, mouseY, delta);
    }
}
