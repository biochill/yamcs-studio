/**
 * Copyright (C) 2010-14 diirt developers. See COPYRIGHT.TXT
 * All rights reserved. Use is subject to license terms. See LICENSE.TXT
 */
package org.yamcs.studio.data.formula.vtable;

import java.util.Arrays;
import java.util.List;

import org.yamcs.studio.data.formula.FormulaFunction;
import org.yamcs.studio.data.vtype.VString;
import org.yamcs.studio.data.vtype.VTable;
import org.yamcs.studio.data.vtype.VTableFactory;
import org.yamcs.studio.data.vtype.VType;

/**
 * Extracts a columns from a VTable.
 */
class TableRangeFilterFunction implements FormulaFunction {

    @Override
    public boolean isVarArgs() {
        return false;
    }

    @Override
    public String getName() {
        return "tableRangeFilter";
    }

    @Override
    public String getDescription() {
        return "Extract the rows where the column value is within the range [min, max)";
    }

    @Override
    public List<Class<?>> getArgumentTypes() {
        return Arrays.<Class<?>> asList(VTable.class, VString.class, VType.class, VType.class);
    }

    @Override
    public List<String> getArgumentNames() {
        return Arrays.asList("table", "columName", "min", "max");
    }

    @Override
    public Class<?> getReturnType() {
        return VTable.class;
    }

    @Override
    public Object calculate(final List<Object> args) {
        VTable table = (VTable) args.get(0);
        VString columnName = (VString) args.get(1);
        VType min = (VType) args.get(2);
        VType max = (VType) args.get(3);

        if (columnName == null || columnName.getValue() == null || table == null || min == null || max == null) {
            return null;
        }

        VTable result = VTableFactory.tableRangeFilter(table, columnName.getValue(), min, max);

        return result;
    }

}
