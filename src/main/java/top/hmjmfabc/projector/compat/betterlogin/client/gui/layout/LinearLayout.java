package top.hmjmfabc.projector.compat.betterlogin.client.gui.layout;

import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;

import java.util.ArrayList;
import java.util.List;

public class LinearLayout extends SupplierChildLinearLayout {
    private final List<ChildContainer> childList = new ArrayList<>();

    public LinearLayout(int pWidth, int pHeight, Orientation pOrientation) {
        super(pWidth, pHeight, pOrientation, ArrayList::new);
        childSupplier = () -> childList;
    }

    public <T extends LayoutElement> void addChild(T child, LayoutSettings settings) {
        childList.add(new ChildContainer(child, settings));
    }

    public <T extends LayoutElement> void addChild(T child) {
        addChild(child, newChildLayoutSettings());
    }
}
