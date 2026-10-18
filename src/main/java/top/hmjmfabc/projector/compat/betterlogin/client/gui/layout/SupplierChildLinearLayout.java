package top.hmjmfabc.projector.compat.betterlogin.client.gui.layout;

import com.mojang.math.Divisor;
import net.minecraft.client.gui.layouts.AbstractLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SupplierChildLinearLayout extends AbstractLayout {
    protected final Orientation orientation;
    protected final LayoutSettings defaultChildLayoutSettings;
    protected Supplier<List<ChildContainer>> childSupplier;

    public SupplierChildLinearLayout(int pWidth, int pHeight, Orientation pOrientation, Supplier<List<ChildContainer>> childSupplier) {
        this(0, 0, pWidth, pHeight, pOrientation, childSupplier);
    }

    public SupplierChildLinearLayout(int pX, int pY, int pWidth, int pHeight, Orientation pOrientation, Supplier<List<ChildContainer>> childSupplier) {
        super(pX, pY, pWidth, pHeight);
        this.orientation = pOrientation;
        this.childSupplier = childSupplier;
        this.defaultChildLayoutSettings = LayoutSettings.defaults();
    }

    @Override
    public void arrangeElements() {
        supperArrangeElements();
        List<ChildContainer> childrens = this.childSupplier.get();
        if (!childrens.isEmpty()) {
            int primaryRealLength = 0;
            int secondaryLength = this.orientation.getSecondaryLength(this);

            ChildContainer childContainer;
            for(Iterator<ChildContainer> iterator = childrens.iterator(); iterator.hasNext(); secondaryLength = Math.max(secondaryLength, this.orientation.getSecondaryLength(childContainer))) {
                childContainer = iterator.next();
                primaryRealLength += this.orientation.getPrimaryLength(childContainer);
            }

            int $$3 = this.orientation.getPrimaryLength(this) - primaryRealLength;
            int $$4 = this.orientation.getPrimaryPosition(this);
            Iterator<ChildContainer> iterator = childrens.iterator();
            ChildContainer $$6 = iterator.next();
            this.orientation.setPrimaryPosition($$6, $$4);
            $$4 += this.orientation.getPrimaryLength($$6);
            ChildContainer $$8;
            if (childrens.size() >= 2) {
                for(Divisor $$7 = new Divisor($$3, childrens.size() - 1); $$7.hasNext(); $$4 += this.orientation.getPrimaryLength($$8)) {
                    $$4 += $$7.nextInt();
                    $$8 = iterator.next();
                    this.orientation.setPrimaryPosition($$8, $$4);
                }
            }

            int $$9 = this.orientation.getSecondaryPosition(this);

            for (ChildContainer container : childrens) {
                this.orientation.setSecondaryPosition(container, $$9, secondaryLength);
            }

            switch (this.orientation) {
                case HORIZONTAL:
                    this.height = secondaryLength;
                    break;
                case VERTICAL:
                    this.width = secondaryLength;
            }
        }
    }

    public void supperArrangeElements() {
        this.visitChildren((element) -> {
            if (element instanceof Layout layout) {
                layout.arrangeElements();
            }
        });
    }

    @Override
    public void visitChildren(Consumer<LayoutElement> pConsumer) {
        this.childSupplier.get().forEach((container) -> {
            pConsumer.accept(container.child);
        });
    }

    public LayoutSettings newChildLayoutSettings() {
        return this.defaultChildLayoutSettings.copy();
    }

    public LayoutSettings defaultChildLayoutSetting() {
        return this.defaultChildLayoutSettings;
    }

    @OnlyIn(Dist.CLIENT)
    public enum Orientation {
        HORIZONTAL,
        VERTICAL;

        Orientation() {
        }

        public int getPrimaryLength(LayoutElement pElement) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pElement.getWidth();
                    break;
                case VERTICAL:
                    var10000 = pElement.getHeight();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }

        public int getPrimaryLength(ChildContainer pCotainer) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pCotainer.getWidth();
                    break;
                case VERTICAL:
                    var10000 = pCotainer.getHeight();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }

        public int getSecondaryLength(LayoutElement pElement) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pElement.getHeight();
                    break;
                case VERTICAL:
                    var10000 = pElement.getWidth();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }

        public int getSecondaryLength(ChildContainer pContainer) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pContainer.getHeight();
                    break;
                case VERTICAL:
                    var10000 = pContainer.getWidth();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }

        public void setPrimaryPosition(ChildContainer pContainer, int pPrimaryPosition) {
            switch (this) {
                case HORIZONTAL:
                    pContainer.setX(pPrimaryPosition, pContainer.getWidth());
                    break;
                case VERTICAL:
                    pContainer.setY(pPrimaryPosition, pContainer.getHeight());
            }

        }

        public void setSecondaryPosition(ChildContainer pContainer, int pSecondaryPosition, int pSecondaryLength) {
            switch (this) {
                case HORIZONTAL:
                    pContainer.setY(pSecondaryPosition, pSecondaryLength);
                    break;
                case VERTICAL:
                    pContainer.setX(pSecondaryPosition, pSecondaryLength);
            }

        }

        public int getPrimaryPosition(LayoutElement pElement) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pElement.getX();
                    break;
                case VERTICAL:
                    var10000 = pElement.getY();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }

        public int getSecondaryPosition(LayoutElement pElement) {
            int var10000;
            switch (this) {
                case HORIZONTAL:
                    var10000 = pElement.getY();
                    break;
                case VERTICAL:
                    var10000 = pElement.getX();
                    break;
                default:
                    throw new IncompatibleClassChangeError();
            }

            return var10000;
        }
    }

    public static class ChildContainer extends AbstractLayout.AbstractChildWrapper {
        public ChildContainer(LayoutElement layoutElement, LayoutSettings layoutSettings) {
            super(layoutElement, layoutSettings);
        }
    }
}
