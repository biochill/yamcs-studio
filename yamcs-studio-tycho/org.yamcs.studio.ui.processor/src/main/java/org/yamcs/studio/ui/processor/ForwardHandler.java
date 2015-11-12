package org.yamcs.studio.ui.processor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.handlers.HandlerUtil;
import org.yamcs.protobuf.Rest.PatchProcessorRequest;
import org.yamcs.protobuf.Yamcs.ReplaySpeed;
import org.yamcs.protobuf.YamcsManagement.ProcessorInfo;
import org.yamcs.studio.core.ConnectionManager;
import org.yamcs.studio.core.model.ManagementCatalogue;
import org.yamcs.studio.core.web.ResponseHandler;
import org.yamcs.studio.core.web.RestClient;

import com.google.protobuf.MessageLite;

// TODO should disable button using state while response is not yet in
public class ForwardHandler extends AbstractHandler {

    private static final Logger log = Logger.getLogger(ForwardHandler.class.getName());

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ProcessorInfo processorInfo = ManagementCatalogue.getInstance().getCurrentProcessorInfo();

        String newSpeed;
        if (processorInfo.getReplayRequest().hasSpeed()) {
            ReplaySpeed currentSpeed = processorInfo.getReplayRequest().getSpeed();
            float speedValue = currentSpeed.getParam() * 2f;
            if (speedValue > 17)
                speedValue = 1.0f;
            newSpeed = (speedValue == 0f ? 1f : speedValue) + "x";
        } else {
            newSpeed = "2x";
        }

        PatchProcessorRequest req = PatchProcessorRequest.newBuilder().setSpeed(newSpeed).build();
        RestClient restClient = ConnectionManager.getInstance().getRestClient();
        restClient.patchProcessorRequest(processorInfo.getInstance(), processorInfo.getName(), req,
                new ResponseHandler() {
                    @Override
                    public void onMessage(MessageLite responseMsg) {
                    }

                    @Override
                    public void onException(Exception e) {
                        log.log(Level.SEVERE, "Could not change speed of processing", e);
                        Display.getDefault().asyncExec(() -> {
                            MessageDialog.openError(HandlerUtil.getActiveShell(event),
                                    "Could not change speed of processing", e.getMessage());
                        });
                    }
                });
        return null;
    }
}
