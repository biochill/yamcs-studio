package org.yamcs.studio.data.yamcs;

import org.yamcs.studio.data.vtype.VLong;
import org.yamcs.protobuf.Pvalue.ParameterValue;

public class Uint32VType extends YamcsVType implements VLong {

    public Uint32VType(ParameterValue pval) {
        super(pval);
    }

    @Override
    public Long getValue() {
        return pval.getEngValue().getUint32Value() & 0xFFFFFFFFL;
    }

    @Override
    public String toString() {
        return Long.toString(pval.getEngValue().getUint32Value() & 0xFFFFFFFFL);
    }
}