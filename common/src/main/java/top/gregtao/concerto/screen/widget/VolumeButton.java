package top.gregtao.concerto.screen.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class VolumeButton extends Button {
    public VolumeButton(int x, int y, int width, int height, OnPress onPress) {
        super(x, y, width, height, Component.literal("♪"), onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderContents(GuiGraphics context, int mouseX, int mouseY, float delta) {
        context.drawCenteredString(Minecraft.getInstance().font, this.getMessage(),
                this.getX() + this.width / 2, this.getY() + (this.height - Minecraft.getInstance().font.lineHeight) / 2,
                this.active ? 0xffffffff : 0xffa0a0a0);
    }
}
