package app.liquidmusic.preview;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

/** Stands in for the NavigationView of Apple's drawer: its menu and its checked item. */
public class StandInNavigationView extends FrameLayout {
    private final Menu menu;
    /** Ids of the items selected through the menu, in order. */
    public final java.util.List<Integer> selections = new java.util.ArrayList<>();

    public StandInNavigationView(Context context) {
        super(context);
        PopupMenu popup = new PopupMenu(context, this);
        menu = popup.getMenu();
        popup.setOnMenuItemClickListener(item -> {
            selections.add(item.getItemId());
            setCheckedItem(item.getItemId());
            return true;
        });
    }

    public Menu getMenu() {
        return menu;
    }

    public MenuItem getCheckedItem() {
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isChecked()) return menu.getItem(i);
        }
        return null;
    }

    public void setCheckedItem(int id) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            item.setCheckable(true);
            item.setChecked(item.getItemId() == id);
        }
    }
}
