package com.professionalpos.ui;

import java.awt.*;

/** A flow row whose preferred height includes controls wrapped onto subsequent rows. */
final class WrapLayout extends FlowLayout {
  WrapLayout() {
    super(FlowLayout.LEADING, 8, 5);
  }

  @Override
  public Dimension preferredLayoutSize(Container target) {
    synchronized (target.getTreeLock()) {
      int width = target.getWidth();
      for (Container parent = target.getParent();
          width <= 0 && parent != null;
          parent = parent.getParent()) width = parent.getWidth();
      if (width <= 0) return super.preferredLayoutSize(target);
      Insets insets = target.getInsets();
      int available = Math.max(1, width - insets.left - insets.right - 2 * getHgap());
      int rowWidth = 0, rowHeight = 0, maxWidth = 0, height = 0;
      for (Component component : target.getComponents()) {
        if (!component.isVisible()) continue;
        Dimension size = component.getPreferredSize();
        int gap = rowWidth == 0 ? 0 : getHgap();
        if (rowWidth > 0 && rowWidth + gap + size.width > available) {
          maxWidth = Math.max(maxWidth, rowWidth);
          height += rowHeight + getVgap();
          rowWidth = 0;
          rowHeight = 0;
          gap = 0;
        }
        rowWidth += gap + size.width;
        rowHeight = Math.max(rowHeight, size.height);
      }
      return new Dimension(
          Math.max(maxWidth, rowWidth) + insets.left + insets.right + 2 * getHgap(),
          height + rowHeight + insets.top + insets.bottom + 2 * getVgap());
    }
  }
}
