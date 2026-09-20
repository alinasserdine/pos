package com.professionalpos.ui;

import com.professionalpos.db.Row;
import com.professionalpos.i18n.I18n;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.*;
import javax.swing.*;
import javax.swing.table.*;

public final class Ui {
  private static final Set<Window> busy = Collections.newSetFromMap(new WeakHashMap<>());

  private Ui() {}

  public static JButton button(String key, Runnable action) {
    JButton b = new JButton(I18n.t(key));
    b.putClientProperty("i18n", key);
    b.addActionListener(
        e -> {
          try {
            action.run();
          } catch (Exception ex) {
            error(b, ex);
          }
        });
    return b;
  }

  public static JLabel label(String key) {
    JLabel l = new JLabel(I18n.t(key));
    l.putClientProperty("i18n", key);
    return l;
  }

  public static JPanel bar(Component... components) {
    JPanel p = new JPanel(new WrapLayout());
    for (Component c : components) p.add(c);
    return p;
  }

  public static <T> void work(Component owner, Callable<T> task, Consumer<T> done) {
    Window w = owner instanceof Window ww ? ww : SwingUtilities.getWindowAncestor(owner);
    if (w != null && !busy.add(w)) return;
    Cursor old = owner.getCursor();
    owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    Map<Component, Boolean> states = new IdentityHashMap<>();
    disable(owner, states);
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return task.call();
      }

      protected void done() {
        states.forEach(Component::setEnabled);
        owner.setCursor(old);
        busy.remove(w);
        try {
          done.accept(get());
        } catch (Exception ex) {
          Throwable cause = ex.getCause() == null ? ex : ex.getCause();
          error(owner, cause);
        }
      }
    }.execute();
  }

  private static void disable(Component c, Map<Component, Boolean> states) {
    if (c instanceof AbstractButton || c instanceof JTextField || c instanceof JComboBox<?>) {
      states.put(c, c.isEnabled());
      c.setEnabled(false);
    }
    if (c instanceof Container ct)
      for (Component child : ct.getComponents()) disable(child, states);
  }

  public static void whenIdle(Component owner, Runnable task) {
    if (!busy(owner)) {
      task.run();
      return;
    }
    javax.swing.Timer timer = new javax.swing.Timer(80, null);
    timer.addActionListener(
        e -> {
          if (!busy(owner)) {
            timer.stop();
            task.run();
          }
        });
    timer.start();
  }

  public static boolean busy(Component c) {
    return busy.contains(c instanceof Window w ? w : SwingUtilities.getWindowAncestor(c));
  }

  public static void error(Component owner, Throwable ex) {
    if (!(ex instanceof IllegalArgumentException || ex instanceof SecurityException))
      Logger.getLogger("POS").log(Level.SEVERE, "Operation failed", ex);
    JOptionPane.showMessageDialog(
        owner, I18n.error(ex), I18n.t("unable_to_complete"), JOptionPane.ERROR_MESSAGE);
  }

  public static void info(Component owner, String message) {
    JOptionPane.showMessageDialog(
        owner, message, I18n.t("professional_pos"), JOptionPane.INFORMATION_MESSAGE);
  }

  public static boolean confirm(Component owner, String key) {
    Object[] opts = {I18n.t("cancel"), I18n.t("confirm")};
    return JOptionPane.showOptionDialog(
            owner,
            I18n.t(key),
            I18n.t("confirm_action"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            opts,
            opts[0])
        == 1;
  }

  public static JTable table() {
    JTable t = new JTable();
    DefaultTableCellRenderer numbers =
        new DefaultTableCellRenderer() {
          @Override
          protected void setValue(Object value) {
            setHorizontalAlignment(SwingConstants.TRAILING);
            setText(
                value instanceof java.math.BigDecimal amount
                    ? amount.stripTrailingZeros().toPlainString()
                    : String.valueOf(value));
          }
        };
    t.setDefaultRenderer(java.math.BigDecimal.class, numbers);
    t.setDefaultRenderer(Number.class, numbers);
    t.setRowHeight(32);
    t.setAutoCreateRowSorter(true);
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    t.getTableHeader().setReorderingAllowed(false);
    t.setFillsViewportHeight(true);
    t.setShowVerticalLines(false);
    t.setIntercellSpacing(new Dimension(0, 1));
    return t;
  }

  public static void rows(JTable table, List<Row> rows) {
    List<String> columns =
        rows.isEmpty() ? List.of("empty") : new ArrayList<>(rows.get(0).keySet());
    table.setModel(
        new AbstractTableModel() {
          public int getRowCount() {
            return rows.size();
          }

          public int getColumnCount() {
            return columns.size();
          }

          public String getColumnName(int col) {
            return I18n.t(columns.get(col));
          }

          public Object getValueAt(int row, int col) {
            Object value = rows.get(row).get(columns.get(col));
            if (value instanceof java.math.BigDecimal d) return d.stripTrailingZeros();
            if (value instanceof Boolean b) return I18n.t(b ? "yes" : "no");
            return value;
          }

          public Class<?> getColumnClass(int column) {
            for (Row r : rows) {
              Object v = r.get(columns.get(column));
              if (v instanceof java.math.BigDecimal) return java.math.BigDecimal.class;
              if (v instanceof Number) return Number.class;
            }
            return Object.class;
          }
        });
    table.putClientProperty("rows", rows);
    table.putClientProperty("columns", columns);
    for (int i = 0; i < columns.size(); i++)
      table.getColumnModel().getColumn(i).setPreferredWidth(i == 0 ? 65 : 150);
    table.setAutoResizeMode(
        columns.size() > 8 ? JTable.AUTO_RESIZE_OFF : JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
  }

  @SuppressWarnings("unchecked")
  public static Row selected(JTable t) {
    int row = t.getSelectedRow();
    if (row < 0) throw new IllegalArgumentException(I18n.t("select_row"));
    return ((List<Row>) t.getClientProperty("rows")).get(t.convertRowIndexToModel(row));
  }

  public static JDialog dialog(
      Component owner, String title, JComponent content, int width, int height) {
    Window parent =
        owner == null
            ? null
            : owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
    JDialog d = new JDialog(parent, I18n.t(title), Dialog.ModalityType.APPLICATION_MODAL);
    d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    d.setContentPane(content);
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    d.setSize(Math.min(width, screen.width - 40), Math.min(height, screen.height - 60));
    d.setLocationRelativeTo(owner);
    d.applyComponentOrientation(I18n.orientation());
    d.getRootPane()
        .registerKeyboardAction(
            e -> {
              if (!busy(d)) d.dispose();
            },
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    return d;
  }
}
