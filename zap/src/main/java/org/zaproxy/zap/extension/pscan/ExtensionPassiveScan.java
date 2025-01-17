/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2010 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.pscan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.ImageIcon;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.FileConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.control.Control.Mode;
import org.parosproxy.paros.core.scanner.Plugin;
import org.parosproxy.paros.core.scanner.Plugin.AlertThreshold;
import org.parosproxy.paros.extension.Extension;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.extension.ExtensionLoader;
import org.parosproxy.paros.extension.SessionChangedListener;
import org.parosproxy.paros.extension.history.ExtensionHistory;
import org.parosproxy.paros.model.Session;
import org.zaproxy.zap.control.CoreFunctionality;
import org.zaproxy.zap.control.ExtensionFactory;
import org.zaproxy.zap.extension.alert.ExtensionAlert;
import org.zaproxy.zap.extension.pscan.scanner.RegexAutoTagScanner;
import org.zaproxy.zap.extension.script.ExtensionScript;
import org.zaproxy.zap.extension.script.ScriptType;
import org.zaproxy.zap.view.ScanStatus;

public class ExtensionPassiveScan extends ExtensionAdaptor implements SessionChangedListener {

    public static final String NAME = "ExtensionPassiveScan";
    public static final String SCRIPT_TYPE_PASSIVE = "passive";
    private static final Logger logger = LogManager.getLogger(ExtensionPassiveScan.class);
    private PassiveScannerList scannerList;
    private OptionsPassiveScan optionsPassiveScan = null;
    private PolicyPassiveScanPanel policyPanel = null;
    private PassiveScanThread pst = null;
    private boolean passiveScanEnabled;
    private PassiveScanParam passiveScanParam;
    private ScanStatus scanStatus = null;
    private static final List<Class<? extends Extension>> DEPENDENCIES;

    static {
        List<Class<? extends Extension>> dep = new ArrayList<>(1);
        dep.add(ExtensionAlert.class);

        DEPENDENCIES = Collections.unmodifiableList(dep);
    }

    private PassiveScannerOptionsPanel passiveScannerOptionsPanel;

    public ExtensionPassiveScan() {
        super();
        initialize();
    }

    private void initialize() {
        this.setOrder(26);
        this.setName(NAME);
    }

    @Override
    public void init() {
        super.init();

        passiveScanEnabled = true;
    }

    @Override
    public String getUIName() {
        return Constant.messages.getString("pscan.name");
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);

        extensionHook.addOptionsParamSet(getPassiveScanParam());

        extensionHook.addProxyListener(getPassiveScanThread());
        extensionHook.addSessionListener(this);
        if (getView() != null) {
            extensionHook.getHookView().addOptionPanel(getPassiveScannerOptionsPanel());
            extensionHook
                    .getHookView()
                    .addOptionPanel(getOptionsPassiveScan(getPassiveScanThread()));
            extensionHook.getHookView().addOptionPanel(getPolicyPanel());
            getView()
                    .getMainFrame()
                    .getMainFooterPanel()
                    .addFooterToolbarRightLabel(getScanStatus().getCountLabel());
        }

        ExtensionScript extScript =
                Control.getSingleton().getExtensionLoader().getExtension(ExtensionScript.class);
        if (extScript != null) {
            extScript.registerScriptType(
                    new ScriptType(
                            SCRIPT_TYPE_PASSIVE,
                            "pscan.scripts.type.passive",
                            createScriptIcon(),
                            true));
        }

        extensionHook.addApiImplementor(new PassiveScanAPI(this));
    }

    private ImageIcon createScriptIcon() {
        if (getView() == null) {
            return null;
        }
        return new ImageIcon(
                ExtensionPassiveScan.class.getResource("/resource/icon/16/script-pscan.png"));
    }

    @Override
    public void optionsLoaded() {
        getPassiveScannerList().setAutoTagScanners(getPassiveScanParam().getAutoTagScanners());
    }

    @Override
    public List<String> getActiveActions() {
        int recordsToScan = getRecordsToScan();
        if (recordsToScan == 0) {
            return Collections.emptyList();
        }

        List<String> activeActions = new ArrayList<>(1);
        activeActions.add(Constant.messages.getString("pscan.activeAction", recordsToScan));
        return activeActions;
    }

    /**
     * @deprecated (2.4.3) Use {@link #addPluginPassiveScanner(PluginPassiveScanner)} instead, the
     *     status of the scanner is not properly set.
     * @see PluginPassiveScanner#getStatus()
     */
    @Deprecated
    @SuppressWarnings("javadoc")
    public boolean addPassiveScanner(String className) {
        try {
            Class<?> c = ExtensionFactory.getAddOnLoader().loadClass(className);
            this.addPassiveScanner((PluginPassiveScanner) c.newInstance());
            return true;

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    public boolean removePassiveScanner(String className) {

        PassiveScanner scanner = getPassiveScannerList().removeScanner(className);

        if (scanner != null && hasView() && scanner instanceof PluginPassiveScanner) {
            getPolicyPanel()
                    .getPassiveScanTableModel()
                    .removeScanner((PluginPassiveScanner) scanner);
        }

        return scanner != null;
    }

    /**
     * Adds the given passive scanner to the list of passive scanners that will be used to scan
     * proxied messages.
     *
     * <p>The passive scanner will not be added if there is already a passive scanner with the same
     * name.
     *
     * <p>If the passive scanner extends from {@code PluginPassiveScanner} it will be added with the
     * method {@code addPluginPassiveScanner(PluginPassiveScanner)}.
     *
     * @param passiveScanner the passive scanner that will be added
     * @return {@code true} if the scanner was added, {@code false} otherwise.
     * @throws IllegalArgumentException if the given passive scanner is {@code null}.
     * @see PluginPassiveScanner
     * @see #addPluginPassiveScanner(PluginPassiveScanner)
     * @see PassiveScanner
     */
    public boolean addPassiveScanner(PassiveScanner passiveScanner) {
        if (passiveScanner == null) {
            throw new IllegalArgumentException("Parameter passiveScanner must not be null.");
        }

        if (passiveScanner instanceof PluginPassiveScanner) {
            return addPluginPassiveScannerImpl((PluginPassiveScanner) passiveScanner);
        }
        return addPassiveScannerImpl(passiveScanner);
    }

    /**
     * Removes the given passive scanner from the list of passive scanners that are used to scan
     * proxied messages.
     *
     * <p>The passive scanners are removed using their class name.
     *
     * @param passiveScanner the passive scanner that will be removed
     * @return {@code true} if the scanner was removed, {@code false} otherwise.
     * @throws IllegalArgumentException if the given passive scanner is {@code null}.
     * @see PassiveScanner
     */
    public boolean removePassiveScanner(PassiveScanner passiveScanner) {
        if (passiveScanner == null) {
            throw new IllegalArgumentException("Parameter passiveScanner must not be null.");
        }
        return removePassiveScanner(passiveScanner.getClass().getName());
    }

    /**
     * Adds the given plug-in passive scanner to the list of passive scanners that will be used to
     * scan proxied messages.
     *
     * <p>The passive scanner will not be added if there is already a passive scanner with the same
     * name.
     *
     * @param pluginPassiveScanner the plug-in passive scanner that will be added
     * @return {@code true} if the plug-in scanner was added, {@code false} otherwise.
     * @throws IllegalArgumentException if the given plug-in passive scanner is {@code null}.
     * @see PluginPassiveScanner
     */
    public boolean addPluginPassiveScanner(PluginPassiveScanner pluginPassiveScanner) {
        if (pluginPassiveScanner == null) {
            throw new IllegalArgumentException("Parameter pluginPassiveScanner must not be null.");
        }
        return addPluginPassiveScannerImpl(pluginPassiveScanner);
    }

    /**
     * Removes the given plug-in passive scanner from the list of passive scanners that are used to
     * scan proxied messages.
     *
     * <p>The plug-in passive scanners are removed using their class name.
     *
     * @param pluginPassiveScanner the passive scanner that will be removed
     * @return {@code true} if the plug-in scanner was removed, {@code false} otherwise.
     * @throws IllegalArgumentException if the given plug-in passive scanner is {@code null}.
     * @see PluginPassiveScanner
     */
    public boolean removePluginPassiveScanner(PluginPassiveScanner pluginPassiveScanner) {
        if (pluginPassiveScanner == null) {
            throw new IllegalArgumentException("Parameter pluginPassiveScanner must not be null.");
        }
        return removePassiveScanner(pluginPassiveScanner.getClass().getName());
    }

    private boolean addPassiveScannerImpl(PassiveScanner passiveScanner) {
        return scannerList.add(passiveScanner);
    }

    private boolean addPluginPassiveScannerImpl(PluginPassiveScanner scanner) {
        if (scanner instanceof RegexAutoTagScanner) {
            return false;
        }

        boolean added = false;
        try {
            FileConfiguration config = this.getModel().getOptionsParam().getConfig();
            scanner.setConfig(config);

            added = addPassiveScannerImpl(scanner);

            if (hasView()) {
                getPolicyPanel().getPassiveScanTableModel().addScanner(scanner);
            }

            logger.info("loaded passive scan rule: {}", scanner.getName());
            if (scanner.getPluginId() == -1) {
                logger.error(
                        "The passive scan rule \"{}\" [{}] does not have a defined ID.",
                        scanner.getName(),
                        scanner.getClass().getCanonicalName());
            }

        } catch (Exception e) {
            logger.error("Failed to load passive scan rule {}", scanner.getName(), e);
        }

        return added;
    }

    private PassiveScannerList getPassiveScannerList() {
        if (scannerList == null) {
            scannerList = new PassiveScannerList();

            // Read from the configs
            scannerList.setAutoTagScanners(getPassiveScanParam().getAutoTagScanners());

            // Load the  'switchable' plugins
            List<PluginPassiveScanner> listTest =
                    new ArrayList<>(CoreFunctionality.getBuiltInPassiveScanRules());
            listTest.addAll(ExtensionFactory.getAddOnLoader().getPassiveScanRules());

            for (PluginPassiveScanner scanner : listTest) {
                addPluginPassiveScannerImpl(scanner);
            }
        }
        return scannerList;
    }

    public List<PluginPassiveScanner> getPluginPassiveScanners() {
        List<PluginPassiveScanner> pluginPassiveScanners = new ArrayList<>();
        for (PassiveScanner scanner : getPassiveScannerList().list()) {
            if ((scanner instanceof PluginPassiveScanner)
                    && !(scanner instanceof RegexAutoTagScanner)) {
                pluginPassiveScanners.add((PluginPassiveScanner) scanner);
            }
        }

        return pluginPassiveScanners;
    }

    /**
     * Sets whether or not all plug-in passive scanners are {@code enabled}.
     *
     * @param enabled {@code true} if the scanners should be enabled, {@code false} otherwise
     */
    void setAllPluginPassiveScannersEnabled(boolean enabled) {
        for (PluginPassiveScanner scanner : getPluginPassiveScanners()) {
            scanner.setEnabled(enabled);
            scanner.save();
        }
    }

    /**
     * Sets whether or not the plug-in passive scanner with the given {@code pluginId} is {@code
     * enabled}.
     *
     * @param pluginId the ID of the plug-in passive scanner
     * @param enabled {@code true} if the scanner should be enabled, {@code false} otherwise
     */
    void setPluginPassiveScannerEnabled(int pluginId, boolean enabled) {
        PluginPassiveScanner scanner = getPluginPassiveScanner(pluginId);
        if (scanner != null) {
            scanner.setEnabled(enabled);
            scanner.save();
        }
    }

    /**
     * Gets the {@code PluginPassiveScanner} with the given ID.
     *
     * @param id the ID of the plugin.
     * @return the {@code PluginPassiveScanner}, or {@code null} if not found (e.g. not installed).
     * @since 2.7.0
     */
    public PluginPassiveScanner getPluginPassiveScanner(int id) {
        for (PluginPassiveScanner scanner : getPluginPassiveScanners()) {
            if (id == scanner.getPluginId()) {
                return scanner;
            }
        }
        return null;
    }

    /**
     * Tells whether or not a plug-in passive scanner with the given {@code pluginId} exist.
     *
     * @param pluginId the ID of the plug-in passive scanner
     * @return {@code true} if the scanner exist, {@code false} otherwise.
     */
    boolean hasPluginPassiveScanner(int pluginId) {
        return getPluginPassiveScanner(pluginId) != null;
    }

    /**
     * Sets the value of {@code alertThreshold} of the plug-in passive scanner with the given {@code
     * pluginId}.
     *
     * <p>If the {@code alertThreshold} is {@code OFF} the scanner is also disabled. The call to
     * this method has no effect if no scanner with the given {@code pluginId} exist.
     *
     * @param pluginId the ID of the plug-in passive scanner
     * @param alertThreshold the alert threshold that will be set
     * @see org.parosproxy.paros.core.scanner.Plugin.AlertThreshold
     */
    void setPluginPassiveScannerAlertThreshold(int pluginId, Plugin.AlertThreshold alertThreshold) {
        PluginPassiveScanner scanner = getPluginPassiveScanner(pluginId);
        if (scanner != null) {
            scanner.setAlertThreshold(alertThreshold);
            scanner.setEnabled(!Plugin.AlertThreshold.OFF.equals(alertThreshold));
            scanner.save();
        }
    }

    /** @param at */
    public void setAllScannerThreshold(AlertThreshold at) {
        for (PluginPassiveScanner test : getPluginPassiveScanners()) {
            test.setAlertThreshold(at);
            test.setEnabled(!AlertThreshold.OFF.equals(at));
            test.save();
        }
    }

    /** @return */
    public AlertThreshold getAllScannerThreshold() {
        AlertThreshold at = null;

        for (PluginPassiveScanner test : getPluginPassiveScanners()) {
            if (at == null) {
                at = test.getAlertThreshold();

            } else if (!at.equals(test.getAlertThreshold())) {
                // Not all the same
                return null;
            }
        }

        return at;
    }

    protected PolicyPassiveScanPanel getPolicyPanel() {
        if (policyPanel == null) {
            policyPanel = new PolicyPassiveScanPanel();
        }
        return policyPanel;
    }

    public int getRecordsToScan() {
        if (passiveScanEnabled) {
            return this.getPassiveScanThread().getRecordsToScan();
        }
        return 0;
    }

    private PassiveScanThread getPassiveScanThread() {
        if (pst == null) {
            final ExtensionLoader extensionLoader = Control.getSingleton().getExtensionLoader();
            final ExtensionHistory extHist = extensionLoader.getExtension(ExtensionHistory.class);
            final ExtensionAlert extAlert = extensionLoader.getExtension(ExtensionAlert.class);

            pst =
                    new PassiveScanThread(
                            getPassiveScannerList(), extHist, extAlert, getPassiveScanParam());

            pst.start();
        }
        return pst;
    }

    PassiveScanParam getPassiveScanParam() {
        if (passiveScanParam == null) {
            passiveScanParam = new PassiveScanParam();
        }
        return passiveScanParam;
    }

    private PassiveScannerOptionsPanel getPassiveScannerOptionsPanel() {
        if (passiveScannerOptionsPanel == null) {
            passiveScannerOptionsPanel = new PassiveScannerOptionsPanel(Constant.messages);
        }
        return passiveScannerOptionsPanel;
    }

    private OptionsPassiveScan getOptionsPassiveScan(PassiveScanThread passiveScanThread) {
        if (optionsPassiveScan == null) {
            optionsPassiveScan = new OptionsPassiveScan(scannerList);
        }
        return optionsPassiveScan;
    }

    @Override
    public void sessionAboutToChange(Session session) {
        stopPassiveScanThread();
    }

    private void stopPassiveScanThread() {
        if (this.pst != null) {
            getPassiveScanThread().shutdown();
            this.pst = null;
        }
    }

    @Override
    public void sessionChanged(Session session) {
        startPassiveScanThread();
        if (hasView()) {
            getScanStatus().setScanCount(0);
        }
    }

    private void startPassiveScanThread() {
        if (passiveScanEnabled && pst == null) {
            // Will create a new thread if one doesn't exist
            getPassiveScanThread();
        }
    }

    public String getCurrentRuleName() {
        return this.getPassiveScanThread().getCurrentRuleName();
    }

    public String getCurrentUrl() {
        return this.getPassiveScanThread().getCurrentUrl();
    }

    public long getCurrentRuleStartTime() {
        return this.getPassiveScanThread().getCurrentRuleStartTime();
    }

    @Override
    public void destroy() {
        super.destroy();

        stopPassiveScanThread();
    }

    @Override
    public List<Class<? extends Extension>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void sessionScopeChanged(Session session) {}

    @Override
    public String getAuthor() {
        return Constant.ZAP_TEAM;
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString("pscan.desc");
    }

    @Override
    public void sessionModeChanged(Mode mode) {
        // Ignore
    }

    void setPassiveScanEnabled(boolean enabled) {
        if (passiveScanEnabled != enabled) {
            passiveScanEnabled = enabled;
            if (enabled) {
                startPassiveScanThread();
            } else {
                stopPassiveScanThread();
            }
        }
    }

    public void saveTo(Configuration conf) {
        for (PassiveScanner scanner : getPassiveScannerList().list()) {
            if ((scanner instanceof PluginPassiveScanner)
                    && !(scanner instanceof RegexAutoTagScanner)) {
                ((PluginPassiveScanner) scanner).saveTo(conf);
            }
        }
    }

    public void loadFrom(Configuration conf) {
        for (PassiveScanner scanner : getPassiveScannerList().list()) {
            if ((scanner instanceof PluginPassiveScanner)
                    && !(scanner instanceof RegexAutoTagScanner)) {
                ((PluginPassiveScanner) scanner).loadFrom(conf);
            }
        }
    }

    protected ScanStatus getScanStatus() {
        if (scanStatus == null) {
            scanStatus =
                    new ScanStatus(
                            new ImageIcon(
                                    ExtensionPassiveScan.class.getResource(
                                            "/resource/icon/16/pscan.png")),
                            Constant.messages.getString("pscan.footer.label"));
        }
        return scanStatus;
    }

    @Override
    public boolean supportsLowMemory() {
        return true;
    }

    /** No database tables used, so all supported */
    @Override
    public boolean supportsDb(String type) {
        return true;
    }
}
