/*
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
package io.trino.plugin.jtopen;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JTOpenQueryResult
{
    private ResultSet fResultSet;

    // Only two row value types are supported right now: String and Integer.
    static enum RowType
    {
        UNKNOWN,
        STRING,
        INTEGER
    }

    // This inner class represents a single column row value.
    static class ROW
    {
        private Object fRowValue;

        public ROW(Object value)
        {
            fRowValue = value;
        }

        public Object getValue()
        {
            return fRowValue;
        }

        public RowType getRowType()
        {
            if (fRowValue instanceof String) {
                return RowType.STRING;
            }
            else if (fRowValue instanceof Integer) {
                return RowType.INTEGER;
            }
            else {
                return RowType.UNKNOWN;
            }
        }

        @Override
        public boolean equals(Object another)
        {
            if (another instanceof ROW) {
                return fRowValue.equals(((ROW) another).getValue());
            }
            else {
                return false;
            }
        }

        @Override
        public int hashCode()
        {
            return fRowValue.hashCode();
        }
    }

    public static ROW row(Object value)
    {
        return new ROW(value);
    }

    public JTOpenQueryResult(ResultSet resultSet)
    {
        fResultSet = resultSet;
    }

    // Assert that the result set has no rows
    public void hasNoRows()
            throws SQLException
    {
        assertFalse(fResultSet.next());
    }

    // Return the raw value on column 1 of the current row.
    private Object getRowValue(RowType rowType)
            throws SQLException
    {
        switch (rowType) {
            case RowType.STRING:
                return fResultSet.getString(1);
            case RowType.INTEGER:
                return fResultSet.getInt(1);
            default:
                return null;
        }
    }

    // Convert the result set to a ROW list
    // Only works for single column result
    private List<ROW> asRows(RowType rowType)
            throws SQLException
    {
        List<ROW> rowList = new ArrayList<ROW>();
        while (fResultSet.next()) {
            Object rowValue = getRowValue(rowType);
            if (rowValue != null) {
                rowList.add(row(rowValue));
            }
        }
        return rowList;
    }

    // Assert that the rows in the current result set match the given row list
    // Only works for single column result.
    public void containsOnly(ROW... rows)
            throws SQLException
    {
        if (rows.length == 0) {
            hasNoRows();
        }

        RowType rowType = rows[0].getRowType();
        List<ROW> resultRows = asRows(rowType);
        List<ROW> targetRows = new ArrayList<ROW>();
        Collections.addAll(targetRows, rows);

        assertEquals(resultRows.size(), targetRows.size());
        assertTrue(resultRows.containsAll(targetRows));
    }
}
