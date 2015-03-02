package org.csstudio.platform.libs.yamcs.vtype;

import org.epics.vtype.VBoolean;
import org.yamcs.protostuff.ParameterValue;

public class BooleanVType extends YamcsVType implements VBoolean {

    public BooleanVType(ParameterValue pval) {
        super(pval);
    }

    @Override
    public Boolean getValue() {
        return pval.getEngValue().getBooleanValue();
    }
    
    @Override
    public String toString() {
        return String.valueOf(pval.getEngValue().getBooleanValue());
    }
}
