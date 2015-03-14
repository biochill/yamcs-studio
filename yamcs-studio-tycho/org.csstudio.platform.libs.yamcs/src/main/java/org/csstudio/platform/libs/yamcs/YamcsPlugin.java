package org.csstudio.platform.libs.yamcs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.csstudio.platform.libs.yamcs.web.RESTClientEndpoint;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.yamcs.protostuff.NamedObjectId;
import org.yamcs.protostuff.RESTService;
import org.yamcs.protostuff.RESTService.ResponseHandler;
import org.yamcs.protostuff.RestDumpRawMdbRequest;
import org.yamcs.protostuff.RestDumpRawMdbResponse;
import org.yamcs.protostuff.RestListAvailableParametersRequest;
import org.yamcs.protostuff.RestListAvailableParametersResponse;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.XtceDb;

public class YamcsPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "org.csstudio.utility.platform.libs.yamcs";
    private static final Logger log = Logger.getLogger(YamcsPlugin.class.getName());

    // The shared instance
    private static YamcsPlugin plugin;

    private RESTService restService;

    private Set<MDBContextListener> mdbListeners = new HashSet<>();

    private List<NamedObjectId> parameterIds = Collections.emptyList();
    private CountDownLatch parametersLoaded = new CountDownLatch(1);

    private Collection<MetaCommand> commands = Collections.emptyList();
    private CountDownLatch commandsLoaded = new CountDownLatch(1);

    public RESTService getRESTService() {
        return restService;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;

        log.info("yamcs plugin starting..");
        String yamcsHost = YamcsPlugin.getDefault().getPreferenceStore().getString("yamcs_host");
        int yamcsPort = YamcsPlugin.getDefault().getPreferenceStore().getInt("yamcs_port");
        String yamcsInstance = YamcsPlugin.getDefault().getPreferenceStore().getString("yamcs_instance");
        restService = new RESTClientEndpoint(new YamcsConnectionProperties(yamcsHost, yamcsPort, yamcsInstance));
        fetchInitialMdbAsync();
    }

    /**
     * Returns the MDB namespace as defined in the user preferences.
     */
    public String getMdbNamespace() {
        return YamcsPlugin.getDefault().getPreferenceStore().getString("mdb_namespace");
    }

    private void fetchInitialMdbAsync() {
        // Load list of parameters
        RestListAvailableParametersRequest req = new RestListAvailableParametersRequest();
        req.setNamespacesList(Arrays.asList(getMdbNamespace()));
        restService.listAvailableParameters(req, new ResponseHandler<RestListAvailableParametersResponse>() {
            @Override
            public void onMessage(RestListAvailableParametersResponse response) {
                Display.getDefault().asyncExec(() -> {
                    parameterIds = response.getIdsList();
                    for (MDBContextListener l : mdbListeners) {
                        l.onParametersChanged(parameterIds);
                    }
                    parametersLoaded.countDown();
                });
            }

            @Override
            public void onFault(Throwable t) {
                log.log(Level.SEVERE, "Could not fetch available yamcs parameters", t);
            }
        });

        // Load commands
        RestDumpRawMdbRequest dumpRequest = new RestDumpRawMdbRequest();
        restService.dumpRawMdb(dumpRequest, new ResponseHandler<RestDumpRawMdbResponse>() {
            @Override
            public void onMessage(RestDumpRawMdbResponse response) {
                // In-memory :-( no easy way to get ByteString as inputstream (?)
                byte[] barray = response.getRawMdb().toByteArray();
                try (ObjectInputStream oin = new ObjectInputStream(new ByteArrayInputStream(barray))) {
                    XtceDb mdb = (XtceDb) oin.readObject();
                    Display.getDefault().asyncExec(() -> {
                        commands = mdb.getMetaCommands();
                        for (MDBContextListener l : mdbListeners) {
                            l.onCommandsChanged(commands);
                        }
                        commandsLoaded.countDown();
                    });
                } catch (IOException | ClassNotFoundException e) {
                    log.log(Level.SEVERE, "Could not deserialize mdb", e);
                }
            }

            @Override
            public void onFault(Throwable t) {
                log.log(Level.SEVERE, "Could not fetch available yamcs commands", t);
            }
        });
    }

    public void addMdbListener(MDBContextListener listener) {
        mdbListeners.add(listener);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        restService.shutdown();
        YRegistrar.getInstance().shutdown();
        super.stop(context);
    }

    public static YamcsPlugin getDefault() {
        return plugin;
    }

    /**
     * Return the available parameters
     */
    public List<NamedObjectId> getParameterIds() {
        return parameterIds;
    }

    public Collection<MetaCommand> getCommands() {
        return commands;
    }
}
