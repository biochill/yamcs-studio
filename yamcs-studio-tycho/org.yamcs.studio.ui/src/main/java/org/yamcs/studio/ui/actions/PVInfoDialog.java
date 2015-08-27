package org.yamcs.studio.ui.actions;

import java.util.ArrayList;
import java.util.List;

import org.csstudio.simplepv.IPV;
import org.csstudio.simplepv.VTypeHelper;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.epics.vtype.Display;
import org.yamcs.protobuf.Rest.RestNameDescription;
import org.yamcs.protobuf.Rest.RestParameterInfo;
import org.yamcs.protobuf.Rest.RestParameterType;
import org.yamcs.protobuf.Rest.RestUnitType;
import org.yamcs.protobuf.Yamcs.NamedObjectId;

public class PVInfoDialog extends Dialog {

    private List<PVInfo> pvInfos;

    public PVInfoDialog(Shell parentShell, String dialogTitle, List<PVInfo> pvInfos) {
        super(parentShell);
        this.pvInfos = pvInfos;
        setShellStyle(SWT.CLOSE | SWT.MODELESS | SWT.BORDER);
        setBlockOnOpen(false);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // Nothing
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        parent.getShell().setText("PV Info");

        if (pvInfos.isEmpty())
            return super.createDialogArea(parent);

        parent.setLayout(new GridLayout());

        Combo combo = null;
        if (pvInfos.size() > 1) {
            combo = new Combo(parent, SWT.READ_ONLY);
            GridData gd = new GridData(GridData.FILL_HORIZONTAL);
            combo.setLayoutData(gd);

            for (PVInfo pvInfo : pvInfos)
                combo.add(pvInfo.getDisplayName());
        }

        // Placeholder for any selected PVs
        Composite pvInfoComposite = new Composite(parent, SWT.NONE);
        GridData gd = new GridData(GridData.FILL_BOTH);
        pvInfoComposite.setLayoutData(gd);
        pvInfoComposite.setLayout(new GridLayout());

        List<Composite> pvComposites = new ArrayList<>();
        for (int i = 0; i < pvInfos.size(); i++)
            pvComposites.add(createPVComposite(pvInfoComposite, pvInfos.get(i), i == 0));

        if (combo != null) {
            final Combo finalCombo = combo; // Grrr
            finalCombo.addListener(SWT.Selection, evt -> {
                for (int i = 0; i < pvComposites.size(); i++) {
                    GridData data = ((GridData) pvComposites.get(i).getLayoutData());

                    data.exclude = (i != finalCombo.getSelectionIndex());

                    pvComposites.get(i).setVisible(!data.exclude);
                }

                // FIXME Commented-out, because i can't get the wrap functional right now
                //pvInfoComposite.layout();
                //pvInfoComposite.getShell().setSize(pvInfoComposite.getShell().computeSize(400, SWT.DEFAULT));
                pvInfoComposite.getShell().pack();
            });

            combo.select(0);
        }

        //pvInfoComposite.getShell().setSize(pvInfoComposite.getShell().computeSize(400, SWT.DEFAULT));

        Rectangle screenSize = pvInfoComposite.getDisplay().getPrimaryMonitor().getBounds();
        Rectangle shellSize = pvInfoComposite.getShell().getBounds();
        pvInfoComposite.getShell().setLocation((screenSize.width - shellSize.width) / 2, (screenSize.height - shellSize.height) / 2);

        return pvInfoComposite;
    }

    private Composite createPVComposite(Composite parent, PVInfo pvInfo, boolean first) {
        Composite pvWrapper = new Composite(parent, SWT.NONE);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        pvWrapper.setLayoutData(gd);

        gd.exclude = !first;
        pvWrapper.setVisible(!gd.exclude);

        GridLayout gl = new GridLayout(2, false);
        gl.marginTop = 10;
        pvWrapper.setLayout(gl);

        if (pvInfo.getParameterInfo() != null) {
            createKeyValueTextPair(pvWrapper, "PV Type", "Yamcs Parameter");
            createSeparator(pvWrapper);
            createYamcsProperties(pvWrapper, pvInfo.getParameterInfo());
        } else if (pvInfo.getParameterInfoException() != null) {
            createKeyValueTextPair(pvWrapper, "PV Type", "Yamcs Parameter");
            createSeparator(pvWrapper);
            createKeyValueTextPair(pvWrapper, "Error", pvInfo.getParameterInfoException());
        } else {
            createKeyValueTextPair(pvWrapper, "PV Type", "PV");
            createSeparator(pvWrapper);
            createPVProperties(pvWrapper, pvInfo.getPV());
        }

        return pvWrapper;
    }

    private void createYamcsProperties(Composite parent, RestParameterInfo pinfo) {
        createKeyValueTextPair(parent, "Data Source", capitalize(pinfo.getDataSource()));
        RestNameDescription desc = pinfo.getDescription();
        createKeyValueTextPair(parent, "Qualified Name", desc.getQualifiedName());
        for (int i = 0; i < desc.getAliasesCount(); i++) {
            NamedObjectId alias = desc.getAliases(i);
            String key = (i == 0) ? "Aliases" : null;
            createKeyValueTextPair(parent, key, alias.getNamespace() + "; " + alias.getName());
        }

        if (desc.hasShortDescription())
            createKeyValueTextPair(parent, "Short Description", desc.getShortDescription());
        if (desc.hasLongDescription())
            createKeyValueTextPair(parent, "Long Description", desc.getLongDescription());
        createSeparator(parent);

        RestParameterType type = pinfo.getType();
        createKeyValueTextPair(parent, "Data Encoding", type.getDataEncoding());
        createKeyValueTextPair(parent, "Engineering Type", capitalize(type.getEngType()));
        if (type.getUnitSetCount() > 0) {
            String units = "";
            for (RestUnitType unit : type.getUnitSetList()) {
                units += unit.getUnit() + " ";
            }
            createKeyValueTextPair(parent, "Units", units);
        }
    }

    private void createPVProperties(Composite parent, IPV pv) {
        StringBuilder stateInfo = new StringBuilder();
        if (!pv.isStarted())
            stateInfo.append("Not started");
        else if (pv.isConnected()) {
            stateInfo.append("Connected");
            if (pv.isPaused())
                stateInfo.append(" Paused");
            else
                stateInfo.append(" Running");
        } else
            stateInfo.append("Connecting");

        createKeyValueTextPair(parent, "Name", pv.getName());
        createKeyValueTextPair(parent, "State", stateInfo.toString());
        createSeparator(parent);

        if (pv.getValue() != null) {
            createKeyValueTextPair(parent, "Last Received Value", pv.getValue().toString());
            Display displayInfo = VTypeHelper.getDisplayInfo(pv.getValue());
            if (displayInfo != null) {
                createKeyValueTextPair(parent, "Units", displayInfo.getUnits());
                createKeyValueTextPair(parent, "Precision", "" + displayInfo.getFormat().getMaximumFractionDigits());
                createKeyValueTextPair(parent, "Display Low", "" + displayInfo.getLowerDisplayLimit());
                createKeyValueTextPair(parent, "Display High", "" + displayInfo.getUpperDisplayLimit());
                createKeyValueTextPair(parent, "Alarm Low", "" + displayInfo.getLowerAlarmLimit());
                createKeyValueTextPair(parent, "Warning Low", "" + displayInfo.getLowerWarningLimit());
                createKeyValueTextPair(parent, "Warning High", "" + displayInfo.getUpperWarningLimit());
                createKeyValueTextPair(parent, "Alarm High", "" + displayInfo.getUpperAlarmLimit());
            }
        }
    }

    private void createKeyValueTextPair(Composite parent, String key, String value) {
        Label lbl = new Label(parent, SWT.NONE);
        if (key != null)
            lbl.setText(key + ": ");
        GridData gd = new GridData();
        gd.horizontalAlignment = SWT.END;
        gd.verticalAlignment = SWT.BEGINNING;
        lbl.setLayoutData(gd);

        // StyledText instead of Label, so that text is selectable
        // TODO should wrap, but can't get it to work right now
        StyledText txt = new StyledText(parent, SWT.WRAP);
        txt.setBackground(parent.getBackground());
        txt.setEditable(false);
        txt.setCaret(null);
        txt.setText(value);
        txt.setWordWrap(true);
    }

    private void createSeparator(Composite parent) {
        Label divider = new Label(parent, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        divider.setLayoutData(gd);
    }

    private static String capitalize(String string) {
        char[] chars = string.toLowerCase().toCharArray();
        boolean found = false;
        for (int i = 0; i < chars.length; i++) {
            if (!found && Character.isLetter(chars[i])) {
                chars[i] = Character.toUpperCase(chars[i]);
                found = true;
            } else if (Character.isWhitespace(chars[i]) || chars[i] == '.' || chars[i] == '\'') { // You can add other chars here
                found = false;
            }
        }
        return String.valueOf(chars);
    }
}