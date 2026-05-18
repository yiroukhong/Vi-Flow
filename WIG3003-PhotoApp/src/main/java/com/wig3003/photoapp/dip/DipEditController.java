package com.wig3003.photoapp.dip;

import com.wig3003.photoapp.dip.aesthetic.DipAestheticController;
import com.wig3003.photoapp.dip.geometric.DipExtractController;
import com.wig3003.photoapp.dip.geometric.DipGeometricController;
import com.wig3003.photoapp.dip.radiometric.DipRadiometricController;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/**
 * Controller for the Edit screen container (DipEdit.fxml).
 * Manages the 5 bottom pill tabs and swaps the center content panel.
 *
 * Tabs:
 *   Radiometric  → Emily
 *   Aesthetic    → Emily
 *   Geometric    → Chyntia (DipGeometric.fxml)
 *   Extraction   → Chyntia (DipExtract.fxml)
 *   Object       → placeholder
 *
 * ═══════════════════════════════════════════════════════════════
 * KEY FIXES
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. TAB CONTROLLERS ARE CACHED
 *    Each tab's FXML is loaded exactly once. Switching tabs swaps the
 *    cached root node into the contentPane — no reload, no state loss,
 *    no image loss, no thread leak from orphaned controllers.
 *
 * 2. LIFECYCLE METHODS ARE CALLED
 *    onTabDeselected() is called on the outgoing controller before
 *    the swap. onTabSelected() is called on the incoming controller
 *    after the swap. This pauses background rendering while a tab is
 *    hidden and resumes it when the tab becomes visible again.
 *
 * 3. SHUTDOWN PROPAGATES
 *    DipEditController.shutdown() calls shutdown() on every cached
 *    tab controller that supports it, so all background threads and
 *    OpenCV Mat memory are released cleanly when the module closes.
 */
public class DipEditController {

    // ── FXML ──────────────────────────────────────────────────────────────

    @FXML private StackPane contentPane;
    @FXML private Label     titleLabel;
    @FXML private Label     statusLabel;
    @FXML private Label     zoomInfo;

    @FXML private Button tabRadiometric;
    @FXML private Button tabAesthetic;
    @FXML private Button tabGeometric;
    @FXML private Button tabExtraction;
    @FXML private Button tabObject;

    // ── STYLES ────────────────────────────────────────────────────────────

    private static final String STYLE_ACTIVE =
        "-fx-font-size:11;-fx-background-color:#c86442;-fx-text-fill:white;" +
        "-fx-padding:6 14;-fx-background-radius:20;-fx-cursor:hand;";

    private static final String STYLE_INACTIVE =
        "-fx-font-size:11;-fx-background-color:rgba(255,255,255,0.06);" +
        "-fx-text-fill:rgba(240,230,216,0.55);-fx-padding:6 14;" +
        "-fx-background-radius:20;-fx-border-color:rgba(255,255,255,0.08);" +
        "-fx-border-radius:20;-fx-cursor:hand;";

    // ── CACHED TAB STATE ──────────────────────────────────────────────────

    /**
     * Cached root nodes for each tab.
     * Loaded once on first visit, reused on every subsequent visit.
     * Key = tab name ("Geometric", "Extraction", etc.)
     */
    private final java.util.Map<String, Parent> cachedRoots =
            new java.util.HashMap<>();

    /**
     * Cached controllers for each tab.
     * Same lifetime as cachedRoots.
     */
    private final java.util.Map<String, Object> cachedControllers =
            new java.util.HashMap<>();

    /** Currently active tab name. Null on first load. */
    private String activeTab = null;

    /** Image path passed from MainController. Null if none selected. */
    private String pendingImagePath = null;

    // ── INITIALIZE ────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Wait for MainController to call setInitialImage() + selectTab().
        // If used standalone (no external caller), selectTab("Geometric")
        // can be called directly.
    }

    // ── PUBLIC API ────────────────────────────────────────────────────────

    /**
     * Called by MainController to pass the selected image path.
     * Must be called BEFORE selectTab().
     */
    public void setInitialImage(String path) {
        this.pendingImagePath = path;
    }

    /**
     * Called by MainController or tab button handlers to switch tabs.
     * Caches the outgoing controller, loads or restores the incoming one.
     */
    public void selectTab(String tab) {
        loadTab(tab);
    }

    /**
     * Called by MainController when the entire Edit module is being
     * shut down (app close or permanent navigation away).
     * Propagates shutdown to all cached tab controllers.
     */
    public void shutdown() {
        for (java.util.Map.Entry<String, Object> entry :
                cachedControllers.entrySet()) {
            try {
                Object ctrl = entry.getValue();
                if (ctrl instanceof DipGeometricController) {
                    ((DipGeometricController) ctrl).shutdown();
                } else if (ctrl instanceof DipExtractController) {
                    ((DipExtractController) ctrl).shutdown();
                } else if (ctrl instanceof DipRadiometricController) {
                    ((DipRadiometricController) ctrl).shutdown();
                } else if (ctrl instanceof DipAestheticController) {
                    ((DipAestheticController) ctrl).shutdown();
                }
            } catch (Exception ignored) {}
        }
        cachedRoots.clear();
        cachedControllers.clear();
    }

    // ── TAB BUTTON HANDLERS ───────────────────────────────────────────────

    @FXML private void handleTabRadiometric() { loadTab("Radiometric"); }
    @FXML private void handleTabAesthetic()   { loadTab("Aesthetic");   }
    @FXML private void handleTabGeometric()   { loadTab("Geometric");   }
    @FXML private void handleTabExtraction()  { loadTab("Extraction");  }
    @FXML private void handleTabObject()      { loadTab("Object");      }

    // ── LOAD / SWAP TAB ───────────────────────────────────────────────────

    private void loadTab(String tab) {
        if (tab.equals(activeTab)) return;   // already on this tab — do nothing

        // ── 1. Notify outgoing controller ─────────────────────────────
        if (activeTab != null) {
            Object outgoing = cachedControllers.get(activeTab);
            if (outgoing instanceof DipGeometricController) {
                ((DipGeometricController) outgoing).onTabDeselected();
            } else if (outgoing instanceof DipExtractController) {
                ((DipExtractController) outgoing).onTabDeselected();
            } else if (outgoing instanceof DipRadiometricController) {
                ((DipRadiometricController) outgoing).onTabDeselected();
            } else if (outgoing instanceof DipAestheticController) {
                ((DipAestheticController) outgoing).onTabDeselected();
            }
        }

        // ── 2. Resolve FXML for this tab ──────────────────────────────
        String fxml = resolveFxml(tab);
        if (fxml == null) {
            activeTab = tab;
            setActiveTabStyle(tab);
            if (titleLabel != null) titleLabel.setText("Edit · " + tab);
            showPlaceholder(tab);
            return;
        }

        // ── 3. Load FXML once, then reuse cached root ─────────────────
        Parent panel = cachedRoots.get(tab);
        Object ctrl  = null;

        if (panel == null) {
            // First visit to this tab — load the FXML
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource(
                                "/com/wig3003/photoapp/fxml/" + fxml));
                panel = loader.load();
                ctrl  = loader.getController();

                cachedRoots.put(tab, panel);
                if (ctrl != null) cachedControllers.put(tab, ctrl);

                // Pass image path on first load only
                if (pendingImagePath != null) {
                    passImageToController(tab, ctrl);
                }

            } catch (Exception e) {
                e.printStackTrace();
                showPlaceholder(tab + " (load error: " + e.getMessage() + ")");
                return;
            }
        } else {
            // Subsequent visit — reuse cached root, no reload
            ctrl = cachedControllers.get(tab);
        }

        // ── 4. Swap into contentPane ──────────────────────────────────
        contentPane.getChildren().setAll(panel);
        activeTab = tab;
        setActiveTabStyle(tab);
        if (titleLabel != null) titleLabel.setText("Edit · " + tab);
        if (statusLabel != null) statusLabel.setText("");

        // ── 5. Notify incoming controller ─────────────────────────────
        if (ctrl instanceof DipGeometricController) {
            ((DipGeometricController) ctrl).onTabSelected();
        } else if (ctrl instanceof DipExtractController) {
            ((DipExtractController) ctrl).onTabSelected();
        } else if (ctrl instanceof DipRadiometricController) {
            ((DipRadiometricController) ctrl).onTabSelected();
        } else if (ctrl instanceof DipAestheticController) {
            ((DipAestheticController) ctrl).onTabSelected();
        }
    }

    // ── PASS IMAGE TO CONTROLLER ──────────────────────────────────────────

    /**
     * Passes the pending image path to the tab's controller.
     * Called only on first load of the tab.
     */
    private void passImageToController(String tab, Object ctrl) {
        try {
            if (tab.equals("Geometric")
                    && ctrl instanceof DipGeometricController) {
                ((DipGeometricController) ctrl)
                        .loadImageFromPath(pendingImagePath);

            } else if (tab.equals("Extraction")
                    && ctrl instanceof DipExtractController) {
                ((DipExtractController) ctrl)
                        .loadImageFromPath(pendingImagePath);
            } else if (tab.equals("Radiometric")
                    && ctrl instanceof DipRadiometricController) {
                ((DipRadiometricController) ctrl)
                        .loadImageFromPath(pendingImagePath);
            } else if (tab.equals("Aesthetic")
                    && ctrl instanceof DipAestheticController) {
                ((DipAestheticController) ctrl)
                        .loadImageFromPath(pendingImagePath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private String resolveFxml(String tab) {
        switch (tab) {
            case "Geometric":   return "DipGeometric.fxml";
            case "Extraction":  return "DipExtract.fxml";
            case "Radiometric": return "DipRadiometric.fxml";
            case "Aesthetic":   return "DipAesthetic.fxml";
            case "Object":      return null;
            default:            return null;
        }
    }

    private void showPlaceholder(String tab) {
        javafx.scene.control.Label placeholder =
                new javafx.scene.control.Label(tab + "\n\nNot yet implemented.");
        placeholder.setStyle(
                "-fx-font-size:16;-fx-text-fill:rgba(240,230,216,0.25);" +
                "-fx-text-alignment:CENTER;-fx-font-family:'Georgia',serif;");
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        contentPane.getChildren().setAll(placeholder);
        if (statusLabel != null) statusLabel.setText("Coming soon");
    }

    private void setActiveTabStyle(String tab) {
        tabRadiometric.setStyle(STYLE_INACTIVE);
        tabAesthetic.setStyle(STYLE_INACTIVE);
        tabGeometric.setStyle(STYLE_INACTIVE);
        tabExtraction.setStyle(STYLE_INACTIVE);
        tabObject.setStyle(STYLE_INACTIVE);
        switch (tab) {
            case "Radiometric": tabRadiometric.setStyle(STYLE_ACTIVE); break;
            case "Aesthetic":   tabAesthetic.setStyle(STYLE_ACTIVE);   break;
            case "Geometric":   tabGeometric.setStyle(STYLE_ACTIVE);   break;
            case "Extraction":  tabExtraction.setStyle(STYLE_ACTIVE);  break;
            case "Object":      tabObject.setStyle(STYLE_ACTIVE);      break;
        }
    }
}