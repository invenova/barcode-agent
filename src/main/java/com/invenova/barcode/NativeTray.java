package com.invenova.barcode;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser.*;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Native Win32 system tray using JNA — no java.desktop required.
 */
public class NativeTray {

    // ── Win32 constants ──────────────────────────────────────────────────────

    private static final int WM_APP         = 0x8000;
    private static final int WM_TRAYICON    = WM_APP + 1;
    private static final int WM_DESTROY     = 0x0002;

    private static final int NIM_ADD        = 0x0;
    private static final int NIM_MODIFY     = 0x1;
    private static final int NIM_DELETE     = 0x2;
    private static final int NIF_MESSAGE    = 0x1;
    private static final int NIF_ICON       = 0x2;
    private static final int NIF_TIP        = 0x4;

    private static final int WM_RBUTTONUP      = 0x0205;
    private static final int WM_CONTEXTMENU    = 0x007B;

    private static final int MF_STRING      = 0x0;
    private static final int MF_SEPARATOR   = 0x800;
    private static final int MF_GRAYED      = 0x1;
    private static final int TPM_RETURNCMD  = 0x0100;
    private static final int TPM_RIGHTBUTTON= 0x0002;
    private static final int TPM_BOTTOMALIGN= 0x0020;

    private static final int IMAGE_ICON      = 1;
    private static final int LR_LOADFROMFILE = 0x10;
    // IDI_APPLICATION = MAKEINTRESOURCE(32512)
    private static final Pointer IDI_APPLICATION = Pointer.createConstant(32512);

    // ── JNA extra interfaces ─────────────────────────────────────────────────

    interface ExtUser32 extends StdCallLibrary {
        ExtUser32 INSTANCE = Native.load("user32", ExtUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        HWND CreateWindowExW(int dwExStyle, WString lpClassName, WString lpWindowName,
                             int dwStyle, int x, int y, int nWidth, int nHeight,
                             HWND hWndParent, HMENU hMenu, HINSTANCE hInstance, Pointer lpParam);

        ATOM RegisterClassExW(WNDCLASSEX lpwcx);
        boolean DestroyWindow(HWND hwnd);
        LRESULT DefWindowProcW(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);
        boolean PostMessageW(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);
        LRESULT DispatchMessageW(MSG msg);
        boolean TranslateMessage(MSG msg);
        int GetMessageW(MSG msg, HWND hwnd, int min, int max);
        boolean GetCursorPos(POINT pt);
        boolean SetForegroundWindow(HWND hwnd);
        HMENU CreatePopupMenu();
        boolean AppendMenuW(HMENU hMenu, int uFlags, int uIDNewItem, WString lpNewItem);
        int TrackPopupMenuEx(HMENU hMenu, int uFlags, int x, int y, HWND hwnd, Pointer tpm);
        boolean DestroyMenu(HMENU hMenu);
        HICON LoadImageW(HINSTANCE hinst, WString name, int type, int cx, int cy, int fuLoad);
        HICON LoadIcon(HINSTANCE hInstance, Pointer iconName);
        boolean DestroyIcon(HICON icon);
        int RegisterWindowMessageW(WString lpString);
    }

    interface ExtShell32 extends StdCallLibrary {
        ExtShell32 INSTANCE = Native.load("shell32", ExtShell32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean Shell_NotifyIconW(int dwMessage, NOTIFYICONDATA lpdata);
    }

    // NOTIFYICONDATA structure (Win32)
    public static class NOTIFYICONDATA extends Structure {
        public int     cbSize = size();
        public HWND    hWnd;
        public int     uID   = 1;
        public int     uFlags;
        public int     uCallbackMessage;
        public HICON   hIcon;
        public char[]  szTip    = new char[128];
        public int     dwState;
        public int     dwStateMask;
        public char[]  szInfo   = new char[256];
        public int     uTimeout;
        public char[]  szInfoTitle = new char[64];
        public int     dwInfoFlags;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cbSize","hWnd","uID","uFlags","uCallbackMessage",
                    "hIcon","szTip","dwState","dwStateMask","szInfo",
                    "uTimeout","szInfoTitle","dwInfoFlags");
        }
    }

    // WNDCLASSEX
    public static class WNDCLASSEX extends Structure {
        public int      cbSize = size();
        public int      style;
        public Callback lpfnWndProc;
        public int      cbClsExtra;
        public int      cbWndExtra;
        public HINSTANCE hInstance;
        public HICON    hIcon;
        public HCURSOR  hCursor;
        public HBRUSH   hbrBackground;
        public WString  lpszMenuName;
        public WString  lpszClassName;
        public HICON    hIconSm;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cbSize","style","lpfnWndProc","cbClsExtra","cbWndExtra",
                    "hInstance","hIcon","hCursor","hbrBackground",
                    "lpszMenuName","lpszClassName","hIconSm");
        }
    }

    interface Callback extends StdCallLibrary.StdCallCallback {
        LRESULT callback(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);
    }

    // ── Menu item model ──────────────────────────────────────────────────────

    public static class MenuItem {
        final String   label;      // null = separator
        volatile boolean enabled;
        final Runnable action;

        private MenuItem(String label, boolean enabled, Runnable action) {
            this.label   = label;
            this.enabled = enabled;
            this.action  = action;
        }

        public static MenuItem item(String label, Runnable action)          { return new MenuItem(label, true,  action); }
        public static MenuItem item(String label, boolean enabled, Runnable action) { return new MenuItem(label, enabled, action); }
        public static MenuItem disabled(String label)                        { return new MenuItem(label, false, null);  }
        public static MenuItem separator()                                   { return new MenuItem(null,  false, null);  }
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final String          tooltip;
    private final String          iconPath;   // absolute path to .ico; null = no icon
    private final List<MenuItem>  menuItems;

    private HWND              hwnd;
    private HICON             hIcon;
    private NOTIFYICONDATA    nid;
    private volatile String   statusText = "";
    private int               wmTaskbarCreated = 0;

    public NativeTray(String tooltip, String iconPath, List<MenuItem> menuItems) {
        this.tooltip   = tooltip;
        this.iconPath  = iconPath;
        this.menuItems = new ArrayList<>(menuItems);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void updateItem(String label, boolean enabled) {
        menuItems.stream().filter(i -> label.equals(i.label)).findFirst()
                .ifPresent(i -> i.enabled = enabled);
    }

    public void setStatus(String text) {
        this.statusText = text;
        if (nid != null) updateTip();
    }

    public void show() {
        Thread t = new Thread(this::messageLoop, "NativeTray-msg");
        t.setDaemon(false);
        t.start();
    }

    public void shutdown() {
        if (hwnd != null) {
            ExtUser32.INSTANCE.PostMessageW(hwnd, WM_DESTROY, new WPARAM(0), new LPARAM(0));
        }
    }

    // ── Message loop ─────────────────────────────────────────────────────────

    private void messageLoop() {
        ExtUser32 u32 = ExtUser32.INSTANCE;

        // Register window class
        Callback wndProc = (hwnd, msg, wp, lp) -> handleMsg(hwnd, msg, wp, lp);
        WNDCLASSEX wc = new WNDCLASSEX();
        wc.lpfnWndProc  = wndProc;
        wc.lpszClassName = new WString("BarcodeAgentTray");
        u32.RegisterClassExW(wc);

        // Create message-only window
        hwnd = u32.CreateWindowExW(0,
                new WString("BarcodeAgentTray"), new WString(""),
                0, 0, 0, 0, 0,
                new HWND(Pointer.createConstant(-3)), // HWND_MESSAGE
                null, null, null);

        // Register for TaskbarCreated so we can re-add the icon if Explorer restarts
        // or wasn't ready yet when the app started at Windows startup.
        wmTaskbarCreated = u32.RegisterWindowMessageW(new WString("TaskbarCreated"));

        // Load icon — request 16x16 (tray icon size) explicitly.
        // LR_DEFAULTSIZE requests SM_CXICON (32x32) which is wrong for the tray.
        // Fall back to IDI_APPLICATION if the custom icon fails to load.
        if (iconPath != null) {
            hIcon = u32.LoadImageW(null, new WString(iconPath), IMAGE_ICON, 16, 16, LR_LOADFROMFILE);
            if (hIcon == null) {
                hIcon = u32.LoadImageW(null, new WString(iconPath), IMAGE_ICON, 32, 32, LR_LOADFROMFILE);
            }
        }
        if (hIcon == null) {
            hIcon = u32.LoadIcon(null, IDI_APPLICATION);
        }

        // Register tray icon — retry up to 10 times (500 ms apart) in case the
        // taskbar isn't ready yet (common when launching at Windows startup).
        nid = new NOTIFYICONDATA();
        nid.hWnd            = hwnd;
        nid.uFlags          = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.uCallbackMessage = WM_TRAYICON;
        nid.hIcon           = hIcon;
        setTip(nid, tooltip);
        boolean added = false;
        for (int attempt = 0; attempt < 10 && !added; attempt++) {
            added = ExtShell32.INSTANCE.Shell_NotifyIconW(NIM_ADD, nid);
            if (!added) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }

        // Message pump
        MSG msg = new MSG();
        while (u32.GetMessageW(msg, null, 0, 0) != 0) {
            u32.TranslateMessage(msg);
            u32.DispatchMessageW(msg);
        }

        // Cleanup
        ExtShell32.INSTANCE.Shell_NotifyIconW(NIM_DELETE, nid);
        if (hIcon != null) u32.DestroyIcon(hIcon);
        if (hwnd  != null) u32.DestroyWindow(hwnd);
    }

    private LRESULT handleMsg(HWND hwnd, int msg, WPARAM wp, LPARAM lp) {
        ExtUser32 u32 = ExtUser32.INSTANCE;
        if (msg == WM_TRAYICON) {
            int event = lp.intValue() & 0xFFFF;
            if (event == WM_RBUTTONUP || event == WM_CONTEXTMENU) {
                showContextMenu();
            }
            return new LRESULT(0);
        }
        if (msg == WM_DESTROY) {
            User32.INSTANCE.PostQuitMessage(0);
            return new LRESULT(0);
        }
        // Re-add the tray icon whenever Explorer (re)creates the taskbar.
        // This handles the startup race condition and Explorer crashes/restarts.
        if (wmTaskbarCreated != 0 && msg == wmTaskbarCreated && nid != null) {
            ExtShell32.INSTANCE.Shell_NotifyIconW(NIM_ADD, nid);
            return new LRESULT(0);
        }
        return u32.DefWindowProcW(hwnd, msg, wp, lp);
    }

    private void showContextMenu() {
        ExtUser32 u32 = ExtUser32.INSTANCE;
        HMENU hMenu = u32.CreatePopupMenu();

        // Status line (greyed)
        String status = statusText;
        if (status != null && !status.isEmpty()) {
            u32.AppendMenuW(hMenu, MF_STRING | MF_GRAYED, 0, new WString(status));
            u32.AppendMenuW(hMenu, MF_SEPARATOR, 0, null);
        }

        // Menu items — cmdId = nextId + i (separators use cmdId 0 and can't be clicked)
        int nextId = 1;
        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            if (item.label == null) {
                u32.AppendMenuW(hMenu, MF_SEPARATOR, 0, null);
            } else {
                int flags = MF_STRING | (item.enabled ? 0 : MF_GRAYED);
                u32.AppendMenuW(hMenu, flags, nextId + i, new WString(item.label));
            }
        }

        POINT pt = new POINT();
        u32.GetCursorPos(pt);
        u32.SetForegroundWindow(hwnd);

        int cmd = u32.TrackPopupMenuEx(hMenu,
                TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_BOTTOMALIGN,
                pt.x, pt.y, hwnd, null);

        u32.DestroyMenu(hMenu);

        // Dispatch — recover item index from cmdId arithmetic
        if (cmd > 0) {
            int idx = cmd - nextId;
            if (idx >= 0 && idx < menuItems.size()) {
                MenuItem item = menuItems.get(idx);
                if (item.action != null) new Thread(item.action, "tray-action").start();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void updateTip() {
        String tip = tooltip + (statusText.isEmpty() ? "" : "\n" + statusText);
        setTip(nid, tip);
        nid.uFlags = NIF_TIP;
        ExtShell32.INSTANCE.Shell_NotifyIconW(NIM_MODIFY, nid);
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    }

    private static void setTip(NOTIFYICONDATA nid, String text) {
        String t = text == null ? "" : text.length() >= 128 ? text.substring(0, 127) : text;
        char[] tip = new char[128];
        System.arraycopy(t.toCharArray(), 0, tip, 0, t.length());
        nid.szTip = tip;
    }
}
