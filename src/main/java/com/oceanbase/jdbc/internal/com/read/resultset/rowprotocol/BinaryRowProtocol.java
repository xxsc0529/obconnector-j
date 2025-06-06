/**
 * OceanBase Client for Java
 * <p>
 * Copyright (c) 2012-2014 Monty Program Ab.
 * Copyright (c) 2015-2020 MariaDB Corporation Ab.
 * Copyright (c) 2021 OceanBase.
 * <p>
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * <p>
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to Monty Program Ab info@montyprogram.com.
 * <p>
 * This particular MariaDB Client for Java file is work
 * derived from a Drizzle-JDBC. Drizzle-JDBC file which is covered by subject to
 * the following copyright and notice provisions:
 * <p>
 * Copyright (c) 2009-2011, Marcus Eriksson
 * <p>
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 * <p>
 * Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 * <p>
 * Neither the name of the driver nor the names of its contributors may not be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS  AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package com.oceanbase.jdbc.internal.com.read.resultset.rowprotocol;

import static com.oceanbase.jdbc.util.Options.ZERO_DATETIME_CONVERT_TO_NULL;
import static com.oceanbase.jdbc.util.Options.ZERO_DATETIME_EXCEPTION;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.TimeZone;

import com.oceanbase.jdbc.Clob;
import com.oceanbase.jdbc.ObArray;
import com.oceanbase.jdbc.ObStruct;
import com.oceanbase.jdbc.OceanBaseConnection;
import com.oceanbase.jdbc.JDBC4ResultSet;
import com.oceanbase.jdbc.extend.datatype.*;
import com.oceanbase.jdbc.internal.ColumnType;
import com.oceanbase.jdbc.internal.com.read.Buffer;
import com.oceanbase.jdbc.internal.com.read.resultset.ColumnDefinition;
import com.oceanbase.jdbc.internal.util.Utils;
import com.oceanbase.jdbc.internal.util.exceptions.ExceptionFactory;
import com.oceanbase.jdbc.util.Options;

public class BinaryRowProtocol extends RowProtocol {

    private final ColumnDefinition[] columnDefinition;
    private final int                columnInformationLength;

    /**
     * Constructor.
     *
     * @param columnDefinition        column information.
     * @param columnInformationLength number of columns
     * @param maxFieldSize            max field size
     * @param options                 connection options
     */
    public BinaryRowProtocol(ColumnDefinition[] columnDefinition, int columnInformationLength,
                             int maxFieldSize, Options options) {
        super(maxFieldSize, options);
        this.columnDefinition = columnDefinition;
        this.columnInformationLength = columnInformationLength;
    }

    /**
     * Set length and pos indicator to asked index.
     *
     * @param newIndex index (0 is first).
     * @see <a href="https://mariadb.com/kb/en/mariadb/resultset-row/">Resultset row protocol
     * documentation</a>
     */
    public void setPosition(int newIndex, JDBC4ResultSet resultSet) throws SQLException {

        // check NULL-Bitmap that indicate if field is null
        if ((buf[1 + (newIndex + 2) / 8] & (1 << ((newIndex + 2) % 8))) != 0) {
            this.lastValueNull = BIT_LAST_FIELD_NULL;
            return;
        }
        boolean readFromHead = false;
        for (int i = 0; i < columnDefinition.length; i++) {
            switch (columnDefinition[i].getColumnType()) {
                case COMPLEX:
                case CURSOR:
                    readFromHead = true;
                    break;
                default:
                    break;
            }
        }

        // if not must parse data until reading the desired field
        int internalPos = 0;
        boolean doRead = true;
        if (readFromHead) {
            index = 0;
            internalPos = 1 + (columnInformationLength + 9) / 8;
        } else {
            if (index != newIndex) {
                internalPos = this.pos;
                if (index == -1 || index > newIndex) {
                    // if there wasn't previous non-null read field, or if last field was after searched index,
                    // position is set on first field position.
                    index = 0;
                    internalPos = 1 + (columnInformationLength + 9) / 8; // 0x00 header + NULL-Bitmap length
                } else {
                    // start at previous non-null field position if was before searched index
                    index++;
                    internalPos += length;
                }
                doRead = true;
            } else {
                doRead = false;
            }
        }
        if (doRead) {
            for (; index <= newIndex; index++) {
                if ((buf[1 + (index + 2) / 8] & (1 << ((index + 2) % 8))) == 0) {
                    if (index != newIndex) {
                        // skip bytes
                        switch (columnDefinition[index].getColumnType()) {
                            case BIGINT:
                            case DOUBLE:
                            case BINARY_DOUBLE:
                                internalPos += 8;
                                break;

                            case INTEGER:
                            case MEDIUMINT:
                            case FLOAT:
                            case NUMBER:
                            case BINARY_FLOAT:
                                internalPos += 4;
                                break;

                            case SMALLINT:
                            case YEAR:
                                internalPos += 2;
                                break;

                            case TINYINT:
                                internalPos += 1;
                                break;
                            // add
                            case COMPLEX:
                                if (complexEndPos == null) {
                                    if (resultSet.complexEndPos[index] == 0) {
                                        resultSet.getComplex(index +  1);
                                    }
                                    this.complexEndPos = resultSet.complexEndPos;
                                }
                                internalPos = this.complexEndPos[index];
                                break;
                            case CURSOR:
                                internalPos = this.complexEndPos[index];
                                break;

                            default:
                                int type = this.buf[internalPos++] & 0xff;
                                switch (type) {
                                    case 251:
                                        break;

                                    case 252:
                                        internalPos += 2 + (0xffff & (((buf[internalPos] & 0xff) + ((buf[internalPos + 1] & 0xff) << 8))));
                                        break;

                                    case 253:
                                        internalPos += 3 + (0xffffff & ((buf[internalPos] & 0xff)
                                                                        + ((buf[internalPos + 1] & 0xff) << 8) + ((buf[internalPos + 2] & 0xff) << 16)));
                                        break;

                                    case 254:
                                        internalPos += 8 + ((buf[internalPos] & 0xff)
                                                            + ((long) (buf[internalPos + 1] & 0xff) << 8)
                                                            + ((long) (buf[internalPos + 2] & 0xff) << 16)
                                                            + ((long) (buf[internalPos + 3] & 0xff) << 24)
                                                            + ((long) (buf[internalPos + 4] & 0xff) << 32)
                                                            + ((long) (buf[internalPos + 5] & 0xff) << 40)
                                                            + ((long) (buf[internalPos + 6] & 0xff) << 48) + ((long) (buf[internalPos + 7] & 0xff) << 56));
                                        break;

                                    default:
                                        internalPos += type;
                                        break;
                                }
                                break;
                        }
                    } else {
                        // read asked field position and length
                        switch (columnDefinition[index].getColumnType()) {
                            case BIGINT:
                            case DOUBLE:
                                this.pos = internalPos;
                                length = 8;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;

                            case INTEGER:
                            case MEDIUMINT:
                            case FLOAT:
                            case NUMBER:
                                this.pos = internalPos;
                                length = 4;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;

                            case SMALLINT:
                            case YEAR:
                                this.pos = internalPos;
                                length = 2;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;

                            case TINYINT:
                                this.pos = internalPos;
                                length = 1;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;
                            case COMPLEX:
                                this.pos = internalPos;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;
                            case CURSOR:
                                this.pos = internalPos;
                                if (this.buf != null && this.buf.length >= this.pos) {
                                    this.length = this.buf[this.pos];
                                }
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;
                            case BINARY_FLOAT:
                                this.pos = internalPos;
                                length = 4;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;
                            case BINARY_DOUBLE:
                                this.pos = internalPos;
                                length = 8;
                                this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                return;

                            default:
                                // field with variable length
                                int typeOrLength = this.buf[internalPos++] & 0xff;
                                switch (typeOrLength) {
                                    case 251:
                                        // null length field
                                        // must never occur
                                        // null value are set in NULL-Bitmap, not send with a null length indicator.
                                        throw new IllegalStateException(
                                            "null data is encoded in binary protocol but NULL-Bitmap is not set");

                                    case 252:
                                        // length is encoded on 3 bytes (0xfc header + 2 bytes indicating length)
                                        length = 0xffff & ((buf[internalPos++] & 0xff) + ((buf[internalPos++] & 0xff) << 8));
                                        this.pos = internalPos;
                                        this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                        return;
                                    case 253:
                                        // length is encoded on 4 bytes (0xfd header + 3 bytes indicating length)
                                        length = 0xffffff & ((buf[internalPos++] & 0xff)
                                                             + ((buf[internalPos++] & 0xff) << 8) + ((buf[internalPos++] & 0xff) << 16));
                                        //                                        length = buf[internalPos++] & 0xff;
                                        this.pos = internalPos;
                                        this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                        return;

                                    case 254:
                                        // length is encoded on 9 bytes (0xfe header + 8 bytes indicating length)
                                        length = (int) ((buf[internalPos++] & 0xff)
                                                        + ((long) (buf[internalPos++] & 0xff) << 8)
                                                        + ((long) (buf[internalPos++] & 0xff) << 16)
                                                        + ((long) (buf[internalPos++] & 0xff) << 24)
                                                        + ((long) (buf[internalPos++] & 0xff) << 32)
                                                        + ((long) (buf[internalPos++] & 0xff) << 40)
                                                        + ((long) (buf[internalPos++] & 0xff) << 48) + ((long) (buf[internalPos++] & 0xff) << 56));
                                        this.pos = internalPos;
                                        this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                        return;

                                    default:
                                        // length is encoded on 1 bytes (is then less than 251)
                                        length = typeOrLength;
                                        this.pos = internalPos;
                                        this.lastValueNull = BIT_LAST_FIELD_NOT_NULL;
                                        return;
                                }
                        }
                    }
                }
            }
        }

        this.lastValueNull = length == NULL_LENGTH ? BIT_LAST_FIELD_NULL : BIT_LAST_FIELD_NOT_NULL;
    }

    /**
     * Get string from raw binary format.
     *
     * @param columnInfo column information
     * @param cal        calendar
     * @param timeZone   time zone
     * @return String value of raw bytes
     * @throws SQLException if conversion failed
     */
    public String getInternalString(ColumnDefinition columnInfo, Calendar cal, TimeZone timeZone)
                                                                                                 throws SQLException {
        Charset charset = getCurrentEncoding(columnInfo.getColumnType());
        if (lastValueWasNull()) {
            switch (columnInfo.getColumnType()) {
                case BINARY_DOUBLE:
                    return Double.toString(0);
                case BINARY_FLOAT:
                    return Float.toString(0);
                default:
                    return null;
            }
        }
        switch (columnInfo.getColumnType()) {
            case BIT:
                return String.valueOf(parseBit());
            case NUMBER:
                return zeroFillingIfNeeded(String.valueOf(getInternalInt(columnInfo)), columnInfo);
            case TINYINT:
                return zeroFillingIfNeeded(String.valueOf(getInternalTinyInt(columnInfo)),
                    columnInfo);
            case SMALLINT:
                return zeroFillingIfNeeded(String.valueOf(getInternalSmallInt(columnInfo)),
                    columnInfo);
            case INTEGER:
            case MEDIUMINT:
                return zeroFillingIfNeeded(String.valueOf(getInternalMediumInt(columnInfo)),
                    columnInfo);
            case BIGINT:
                if (!columnInfo.isSigned()) {
                    return zeroFillingIfNeeded(String.valueOf(getInternalBigInteger(columnInfo)),
                        columnInfo);
                }
                return zeroFillingIfNeeded(String.valueOf(getInternalLong(columnInfo)), columnInfo);
            case FLOAT:
                return zeroFillingIfNeeded(String.valueOf(getInternalFloat(columnInfo)), columnInfo);
            case NUMBER_FLOAT:
            case DECIMAL:
            case OLDDECIMAL:
            case OBDECIMAL:
                BigDecimal bigDecimal = getInternalBigDecimal(columnInfo);
                return (bigDecimal == null) ? null : zeroFillingIfNeeded(bigDecimal.toString(),
                    columnInfo);
            case BINARY_FLOAT:
                Float f = getInternalFloat(columnInfo);
                return Float.toString(f);
            case DOUBLE:
                return zeroFillingIfNeeded(String.valueOf(getInternalDouble(columnInfo)),
                    columnInfo);
            case BINARY_DOUBLE:
                Double d = getInternalDouble(columnInfo);
                return Double.toString(d);
            case TIME:
                return getInternalTimeString(columnInfo);
            case DATE:
                if (length == 0) {
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                        return "0000-00-00";
                    }
                    if (options.zeroDateTimeBehavior.equalsIgnoreCase(ZERO_DATETIME_EXCEPTION)) {
                        throw new SQLException("Value '0000-00-00' can not be represented as java.sql.Date");
                    }
                }
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    useCalLenientFlag = true;
                }
                Date date = getInternalDate(columnInfo, cal, TimeZone.getDefault());
                if (date == null) {
                    return null;
                }
                String dateStr = date.toString();
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    if (yearIsZero) {
                        yearIsZero = false;
                        String[] split = dateStr.split("-");
                        if ("0001".equals(split[0])) {
                            dateStr = dateStr.replaceFirst("0001", "0000");
                        }
                    }
                }
                return dateStr;
            case YEAR:
                if (options.yearIsDateType) {
                    Date dateInter = getInternalDate(columnInfo, cal, TimeZone.getDefault());
                    return (dateInter == null) ? null : dateInter.toString();
                } else if (options.compatibleMysqlVersion == 5) {
                    int internalSmallInt = getInternalSmallInt(columnInfo);
                    return zeroFillingIfNeeded(String.valueOf(internalSmallInt), columnInfo);
                }
                return String.valueOf(getInternalSmallInt(columnInfo));
            case TIMESTAMP:
            case TIMESTAMP_NANO:
            case DATETIME:
                if (length == 0 && !getProtocol().isOracleMode()
                    && options.compatibleMysqlVersion == 8) {
                    return "0000-00-00 00:00:00";
                }
                if (columnInfo.getColumnType() == ColumnType.DATETIME && !options.mapDateToTimestamp && getProtocol().isOracleMode()) {
                    Date dateInter = getInternalDate(columnInfo, cal, TimeZone.getDefault());
                    return (dateInter == null) ? null : dateInter.toString();
                }
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    useCalLenientFlag = true;
                }
                Timestamp timestamp = getInternalTimestamp(columnInfo, cal, TimeZone.getDefault());
                if (timestamp == null) {
                    return null;
                }

                if (getProtocol().isOracleMode()) {
                    LocalDateTime localDateTime = timestamp.toLocalDateTime();
                    int nano = localDateTime.getNano();
                    if (nano == 0
                        && (columnInfo.getColumnType() == ColumnType.TIMESTAMP_NANO || options.compatibleOjdbcVersion == 8)) {
                        nano = -1;
                    }
                    String str = DataTypeUtilities.TIMESTAMPTZToString(localDateTime.getYear(),
                        localDateTime.getMonthValue(), localDateTime.getDayOfMonth(),
                        localDateTime.getHour(), localDateTime.getMinute(),
                        localDateTime.getSecond(), nano, 0, null);
                    return str;
                } else if (options.compatibleMysqlVersion == 8) {
                    if (columnInfo.getLength() >= 19) {
                        String[] timeSplit = timestamp.toString().split("\\.");
                        String datetime = timeSplit[0];
                        StringBuilder millisecond = new StringBuilder(timeSplit[1]);
                        if (!millisecond.toString().matches("0+")) {
                            for (int i = millisecond.length(); i < columnInfo.getPrimitiveDecimals(); i++) {
                                millisecond.append(0);
                            }
                            datetime = datetime + "." + millisecond;
                        }
                        if (yearIsZero) {
                            yearIsZero = false;
                            String[] split = datetime.split("-");
                            if ("0001".equals(split[0])) {
                                datetime = datetime.replaceFirst("0001", "0000");
                            }
                        }
                        return datetime;
                    }
                }
                return timestamp.toString();
            case INTERVALDS:
                INTERVALDS intervalds = getInternalINTERVALDS(columnInfo);
                return intervalds.toString();
            case INTERVALYM:
                INTERVALYM intervalym = getInternalINTERVALYM(columnInfo);
                return intervalym.toString();

            case NULL:
                return null;

            case VARSTRING:
                return new String(buf, pos, length, getCurrentEncoding(columnInfo.getColumnType()));
            case STRING:
                if (getMaxFieldSize() > 0) {
                    return new String(buf, pos, Math.min(getMaxFieldSize() * 3, length),
                        getCurrentEncoding(columnInfo.getColumnType())).substring(0,
                        Math.min(getMaxFieldSize(), length));
                }
                return new String(buf, pos, length, getCurrentEncoding(columnInfo.getColumnType()));
            case RAW:
                byte[] returnBytes = new byte[length];
                System.arraycopy(buf, pos, returnBytes, 0, length);
                return Utils.toHexString(returnBytes);
            case ORA_CLOB:
                if (options.supportLobLocator) {
                    String encoding = this.options.getCharacterEncoding();
                    byte[] data = new byte[buf.length];
                    System.arraycopy(buf, pos, data, 0, length);
                    Clob c = new com.oceanbase.jdbc.Clob(true, data, encoding, null);
                    return c.toString();
                } else {
                    return new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                }

            case GEOMETRY:
                return new String(buf, pos, length);
            case COMPLEX:
                if (this.options.compatibleOjdbcVersion == 8) {
                    throw new SQLFeatureNotSupportedException();
                }
                return null;

            default:
                if (getMaxFieldSize() > 0) {
                    return new String(buf, pos, Math.min(getMaxFieldSize() * 3, length), charset)
                        .substring(0, Math.min(getMaxFieldSize(), length));
                }
                return new String(buf, pos, length, charset);
        }
    }

    /**
     * Get int from raw binary format.
     *
     * @param columnInfo column information
     * @return int value
     * @throws SQLException if column is not numeric or is not in Integer bounds.
     */
    public int getInternalInt(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }
        try {
            long value;
            switch (columnInfo.getColumnType()) {
                case BIT:
                    value = parseBit();
                    break;
                case TINYINT:
                    value = getInternalTinyInt(columnInfo);
                    break;
                case SMALLINT:
                case YEAR:
                    value = getInternalSmallInt(columnInfo);
                    break;
                case INTEGER:
                case MEDIUMINT:
                case NUMBER:
                    value = ((buf[pos] & 0xff) + ((buf[pos + 1] & 0xff) << 8)
                             + ((buf[pos + 2] & 0xff) << 16) + ((buf[pos + 3] & 0xff) << 24));
                    if (columnInfo.isSigned()) {
                        return (int) value;
                    } else if (value < 0) {
                        value = value & 0xffffffffL;
                    }
                    break;
                case BIGINT:
                    value = getInternalLong(columnInfo);
                    break;
                case FLOAT:
                    value = (long) getInternalFloat(columnInfo);
                    break;
                case DOUBLE:
                    value = (long) getInternalDouble(columnInfo);
                    break;
                case BINARY_FLOAT:
                case BINARY_DOUBLE:
                    double internalDouble = getInternalDouble(columnInfo);
                    return (int) internalDouble;
                case NUMBER_FLOAT:
                case DECIMAL:
                case OLDDECIMAL:
                case OBDECIMAL:
                    BigDecimal bigDecimal = getInternalBigDecimal(columnInfo);
                    rangeCheck(Integer.class, Integer.MIN_VALUE, Integer.MAX_VALUE, bigDecimal,
                        columnInfo);
                    return bigDecimal.intValue();
                case VARSTRING:
                case VARCHAR:
                case STRING:
                case VARCHAR2:
                case NVARCHAR2:
                    if (length == 0) {
                        return convertToZeroWithEmptyCheck();
                    }
                    value = Long.parseLong(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                    break;
                default:
                    throw new SQLException("getInt not available for data field type "
                                           + columnInfo.getColumnType().getSqlTypeName());
            }
            rangeCheck(Integer.class, Integer.MIN_VALUE, Integer.MAX_VALUE, value, columnInfo);
            return (int) value;
        } catch (NumberFormatException nfe) {
            // parse error.
            // if its a decimal retry without the decimal part.
            String stringVal = new String(buf, pos, length,
                getCurrentEncoding(columnInfo.getColumnType()));
            if (isIntegerRegex.matcher(stringVal).find()) {
                try {
                    return Integer.parseInt(stringVal.substring(0, stringVal.indexOf(".")));
                } catch (NumberFormatException nfee) {
                    // eat exception
                }
            }
            if (!getProtocol().isOracleMode()) {
                try {
                    double doubleVal = Double.parseDouble(stringVal);
                    // check
                    if (options.jdbcCompliantTruncation) {
                        rangeCheck(Integer.class, Integer.MIN_VALUE, Integer.MAX_VALUE, doubleVal,
                            columnInfo);
                    }
                    return (int) doubleVal;
                } catch (NumberFormatException e) {
                    throw new SQLException("Out of range value for column '" + columnInfo.getName()
                                           + "' : value " + stringVal, "22003", 1264);
                }
            }
            throw new SQLException("Out of range value for column '" + columnInfo.getName()
                                   + "' : value " + stringVal, "22003", 1264);

        }
    }

    /**
     * Get long from raw binary format.
     *
     * @param columnInfo column information
     * @return long value
     * @throws SQLException if column is not numeric or is not in Long bounds (for big unsigned
     *                      values)
     */
    public long getInternalLong(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }

        long value;
        try {
            switch (columnInfo.getColumnType()) {
                case BIT:
                    return parseBit();
                case TINYINT:
                    value = getInternalTinyInt(columnInfo);
                    break;
                case SMALLINT:
                case YEAR:
                    value = getInternalSmallInt(columnInfo);
                    break;
                case INTEGER:
                case MEDIUMINT:
                    value = getInternalMediumInt(columnInfo);
                    break;
                case BIGINT:
                    value = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                             + ((long) (buf[pos + 2] & 0xff) << 16)
                             + ((long) (buf[pos + 3] & 0xff) << 24)
                             + ((long) (buf[pos + 4] & 0xff) << 32)
                             + ((long) (buf[pos + 5] & 0xff) << 40)
                             + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                    if (columnInfo.isSigned()) {
                        return value;
                    }
                    BigInteger unsignedValue = new BigInteger(1, new byte[] { (byte) (value >> 56),
                            (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
                            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                            (byte) value });
                    if (unsignedValue.compareTo(new BigInteger(String.valueOf(Long.MAX_VALUE))) > 0) {
                        throw new SQLException("Out of range value for column '"
                                               + columnInfo.getName() + "' : value "
                                               + unsignedValue + " is not in Long range", "22003",
                            1264);
                    }
                    return unsignedValue.longValue();
                case FLOAT:
                    Float floatValue = getInternalFloat(columnInfo);
                    if (floatValue.compareTo((float) Long.MAX_VALUE) >= 1) {
                        throw new SQLException("Out of range value for column '"
                                               + columnInfo.getName() + "' : value " + floatValue
                                               + " is not in Long range", "22003", 1264);
                    }
                    return floatValue.longValue();
                case DOUBLE:
                    Double doubleValue = getInternalDouble(columnInfo);
                    if (doubleValue.compareTo((double) Long.MAX_VALUE) >= 1) {
                        throw new SQLException("Out of range value for column '"
                                               + columnInfo.getName() + "' : value " + doubleValue
                                               + " is not in Long range", "22003", 1264);
                    }
                    return doubleValue.longValue();
                case NUMBER_FLOAT:
                case DECIMAL:
                case OLDDECIMAL:
                    BigDecimal bigDecimal = getInternalBigDecimal(columnInfo);
                    rangeCheck(Long.class, Long.MIN_VALUE, Long.MAX_VALUE, bigDecimal, columnInfo);
                    return bigDecimal.longValue();
                case VARSTRING:
                case VARCHAR:
                case STRING:
                case VARCHAR2:
                case NVARCHAR2:
                    if (length == 0) {
                        return convertToZeroWithEmptyCheck();
                    }
                case BINARY_FLOAT:
                case BINARY_DOUBLE:
                    Double internalDouble = getInternalDouble(columnInfo);
                    return internalDouble.longValue();
                case OBDECIMAL:
                    return Long.parseLong(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                default:
                    throw new SQLException("getLong not available for data field type "
                                           + columnInfo.getColumnType().getSqlTypeName());
            }
            rangeCheck(Long.class, Long.MIN_VALUE, Long.MAX_VALUE, value, columnInfo);
            return value;
        } catch (NumberFormatException nfe) {
            // parse error.
            // if its a decimal retry without the decimal part.
            String stringVal = new String(buf, pos, length,
                getCurrentEncoding(columnInfo.getColumnType()));
            if (isIntegerRegex.matcher(stringVal).find()) {
                try {
                    return Long.parseLong(stringVal.substring(0, stringVal.indexOf(".")));
                } catch (NumberFormatException nfee) {
                    // eat exception
                }
            }
            try {
                double doubleVal = Double.parseDouble(stringVal);
                // check
                if (!getProtocol().isOracleMode() && options.jdbcCompliantTruncation) {
                    rangeCheck(Long.class, Long.MIN_VALUE, Long.MAX_VALUE, doubleVal,
                        columnInfo);
                }
                return (long) doubleVal;
            } catch (NumberFormatException e) {
                throw new SQLException("Out of range value for column '" + columnInfo.getName()
                                       + "' : value " + stringVal, "22003", 1264);
            }
        }
    }

    /**
     * Get float from raw binary format.
     *
     * @param columnInfo column information
     * @return float value
     * @throws SQLException if column is not numeric or is not in Float bounds.
     */
    public float getInternalFloat(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }

        long value;
        switch (columnInfo.getColumnType()) {
            case BIT:
                return parseBit();
            case TINYINT:
                value = getInternalTinyInt(columnInfo);
                break;
            case SMALLINT:
            case YEAR:
                value = getInternalSmallInt(columnInfo);
                break;
            case INTEGER:
            case MEDIUMINT:
                value = getInternalMediumInt(columnInfo);
                break;
            case BIGINT:
                value = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                         + ((long) (buf[pos + 2] & 0xff) << 16)
                         + ((long) (buf[pos + 3] & 0xff) << 24)
                         + ((long) (buf[pos + 4] & 0xff) << 32)
                         + ((long) (buf[pos + 5] & 0xff) << 40)
                         + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                if (columnInfo.isSigned()) {
                    return value;
                }
                BigInteger unsignedValue = new BigInteger(1, new byte[] { (byte) (value >> 56),
                        (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
                        (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                        (byte) value });
                return unsignedValue.floatValue();
            case FLOAT:
                int valueFloat = ((buf[pos] & 0xff) + ((buf[pos + 1] & 0xff) << 8)
                                  + ((buf[pos + 2] & 0xff) << 16) + ((buf[pos + 3] & 0xff) << 24));
                return Float.intBitsToFloat(valueFloat);
            case BINARY_FLOAT:
                int asInt = (buf[pos + 0] & 0xff) | ((buf[pos + 1] & 0xff) << 8)
                            | ((buf[pos + 2] & 0xff) << 16) | ((buf[pos + 3] & 0xff) << 24);
                return Float.intBitsToFloat(asInt);
            case NUMBER_FLOAT:
                String str = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                Float f = Float.valueOf(str);
                return f;
            case DOUBLE:
                return (float) getInternalDouble(columnInfo);
            case VARSTRING:
            case VARCHAR:
            case STRING:
            case VARCHAR2:
            case NVARCHAR2:
                if (length == 0) {
                    return convertToZeroWithEmptyCheck();
                }
            case DECIMAL:
                try {
                    return Float.valueOf(new String(buf, pos, length, getCurrentEncoding(columnInfo
                        .getColumnType())));
                } catch (NumberFormatException nfe) {
                    SQLException sqlException = new SQLException(
                        "Incorrect format for getFloat for data field with type "
                                + columnInfo.getColumnType().getSqlTypeName(), "22003", 1264, nfe);
                    throw sqlException;
                }
            case OBDECIMAL:
                String val = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                BigDecimal bigDecimal = new BigDecimal(val);
                return bigDecimal.floatValue();
            default:
                throw new SQLException("getFloat not available for data field type "
                                       + columnInfo.getColumnType().getSqlTypeName());
        }
        try {
            return Float.valueOf(String.valueOf(value));
        } catch (NumberFormatException nfe) {
            SQLException sqlException = new SQLException(
                "Incorrect format for getFloat for data field with type "
                        + columnInfo.getColumnType().getSqlTypeName(), "22003", 1264, nfe);
            throw sqlException;
        }
    }

    /**
     * Get double from raw binary format.
     *
     * @param columnInfo column information
     * @return double value
     * @throws SQLException if column is not numeric or is not in Double bounds (unsigned columns).
     */
    public double getInternalDouble(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }
        switch (columnInfo.getColumnType()) {
            case BIT:
                return parseBit();
            case TINYINT:
                return getInternalTinyInt(columnInfo);
            case SMALLINT:
            case YEAR:
                return getInternalSmallInt(columnInfo);
            case INTEGER:
            case MEDIUMINT:
                return getInternalMediumInt(columnInfo);
            case BIGINT:
                long valueLong = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                                  + ((long) (buf[pos + 2] & 0xff) << 16)
                                  + ((long) (buf[pos + 3] & 0xff) << 24)
                                  + ((long) (buf[pos + 4] & 0xff) << 32)
                                  + ((long) (buf[pos + 5] & 0xff) << 40)
                                  + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                if (columnInfo.isSigned()) {
                    return valueLong;
                } else {
                    return new BigInteger(1, new byte[] { (byte) (valueLong >> 56),
                            (byte) (valueLong >> 48), (byte) (valueLong >> 40),
                            (byte) (valueLong >> 32), (byte) (valueLong >> 24),
                            (byte) (valueLong >> 16), (byte) (valueLong >> 8), (byte) valueLong })
                        .doubleValue();
                }
            case FLOAT:
                return getInternalFloat(columnInfo);
            case DOUBLE:
                long valueDouble = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                                    + ((long) (buf[pos + 2] & 0xff) << 16)
                                    + ((long) (buf[pos + 3] & 0xff) << 24)
                                    + ((long) (buf[pos + 4] & 0xff) << 32)
                                    + ((long) (buf[pos + 5] & 0xff) << 40)
                                    + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                return Double.longBitsToDouble(valueDouble);
            case VARSTRING:
            case VARCHAR:
            case STRING:
            case VARCHAR2:
            case NVARCHAR2:
                if (length == 0) {
                    return convertToZeroWithEmptyCheck();
                }
            case DECIMAL:
                try {
                    return Double.valueOf(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())));
                } catch (NumberFormatException nfe) {
                    SQLException sqlException = new SQLException(
                        "Incorrect format for getDouble for data field with type "
                                + columnInfo.getColumnType().getSqlTypeName(), "22003", 1264);
                    //noinspection UnnecessaryInitCause
                    sqlException.initCause(nfe);
                    throw sqlException;
                }
            case BINARY_DOUBLE:
                long valueAsLong = (buf[pos + 0] & 0xff) | ((long) (buf[pos + 1] & 0xff) << 8)
                                   | ((long) (buf[pos + 2] & 0xff) << 16)
                                   | ((long) (buf[pos + 3] & 0xff) << 24)
                                   | ((long) (buf[pos + 4] & 0xff) << 32)
                                   | ((long) (buf[pos + 5] & 0xff) << 40)
                                   | ((long) (buf[pos + 6] & 0xff) << 48)
                                   | ((long) (buf[pos + 7] & 0xff) << 56);

                return Double.longBitsToDouble(valueAsLong);
            case BINARY_FLOAT:
                return getInternalFloat(columnInfo);
                //                return Double.parseDouble(new String(buf, pos, length, getCurrentEncoding(columnInfo.getColumnType())));
            case OBDECIMAL:
            case NUMBER_FLOAT:
                String val = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                BigDecimal bigDecimal = new BigDecimal(val);
                return bigDecimal.doubleValue();
            default:
                throw new SQLException("getDouble not available for data field type "
                                       + columnInfo.getColumnType().getSqlTypeName());
        }
    }

    /**
     * Get BigDecimal from raw binary format.
     *
     * @param columnInfo column information
     * @return BigDecimal value
     * @throws SQLException if column is not numeric
     */
    public BigDecimal getInternalBigDecimal(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }

        switch (columnInfo.getColumnType()) {
            case BIT:
                return BigDecimal.valueOf(parseBit());
            case TINYINT:
                return BigDecimal.valueOf(getInternalTinyInt(columnInfo));
            case SMALLINT:
            case YEAR:
                return BigDecimal.valueOf(getInternalSmallInt(columnInfo));
            case INTEGER:
            case MEDIUMINT:
                return BigDecimal.valueOf(getInternalMediumInt(columnInfo));
            case BIGINT:
                long value = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                              + ((long) (buf[pos + 2] & 0xff) << 16)
                              + ((long) (buf[pos + 3] & 0xff) << 24)
                              + ((long) (buf[pos + 4] & 0xff) << 32)
                              + ((long) (buf[pos + 5] & 0xff) << 40)
                              + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                if (columnInfo.isSigned()) {
                    BigDecimal decimal = new BigDecimal(String.valueOf(BigInteger.valueOf(value)));
                    return setScaleOrHandleException(decimal, columnInfo.getDecimals());
                } else {
                    BigDecimal decimal = new BigDecimal(String.valueOf(new BigInteger(1, new byte[]{
                            (byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40),
                            (byte) (value >> 32), (byte) (value >> 24), (byte) (value >> 16),
                            (byte) (value >> 8), (byte) value})));
                    return setScaleOrHandleException(decimal, columnInfo.getDecimals());
                }
            case FLOAT:
            case BINARY_FLOAT:
                Float f = getInternalFloat(columnInfo);
                return new BigDecimal(f.toString());
            case DOUBLE:
            case BINARY_DOUBLE:
                BigDecimal decimal = BigDecimal.valueOf(getInternalDouble(columnInfo));
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 5) {
                    return setScaleOrHandleException(decimal, columnInfo.getDecimals());
                }
                return decimal;
            case VARSTRING:
            case VARCHAR:
            case VARCHAR2:
            case NVARCHAR2:
            case STRING:
                if (length == 0) {
                    return new BigDecimal(convertToZeroWithEmptyCheck());
                }
            case DECIMAL:
            case OLDDECIMAL:
            case OBDECIMAL:
            case NUMBER_FLOAT:
                String strValue = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType())).trim();
                try {
                    BigDecimal retVal = new BigDecimal(strValue);
                    return retVal;
                } catch (Exception e) {
                    throw new SQLException("Bad format for BigDecimal '" + strValue + "'");
                }
            default:
                throw new SQLException("getBigDecimal not available for data field type "
                                       + columnInfo.getColumnType().getSqlTypeName());
        }
    }

    /**
     * Get date from raw binary format.
     *
     * @param columnInfo column information
     * @param cal        calendar
     * @param timeZone   time zone
     * @return date value
     * @throws SQLException if column is not compatible to Date
     */
    @SuppressWarnings("deprecation")
    public Date getInternalDate(ColumnDefinition columnInfo, Calendar cal, TimeZone timeZone)
                                                                                             throws SQLException {
        try {
            if (lastValueWasNull()) {
                return null;
            }
            if (getProtocol().isOracleMode()) {
                if (columnInfo.getColumnType() == ColumnType.INTERVALYM || columnInfo.getColumnType() == ColumnType.INTERVALDS) {
                    throw new SQLException("Invalid column type, getDate not implemented for this type.");
                }
            }
            switch (columnInfo.getColumnType()) {
                case TIMESTAMP:
                case TIMESTAMP_NANO:
                case DATETIME:
                case TIMESTAMP_TZ:
                case TIMESTAMP_LTZ:
                    Timestamp timestamp = getInternalTimestamp(columnInfo, cal, timeZone);
                    if (timestamp != null && getProtocol().isOracleMode()) {
                        timestamp.setNanos(0);
                        if (options.compatibleOjdbcVersion == 6) {
                            timestamp.setHours(0);
                            timestamp.setMinutes(0);
                            timestamp.setSeconds(0);
                        }
                    }
                    return (timestamp == null) ? null : new Date(timestamp.getTime());
                case TIME:
                    return new Date(0L);
                case STRING:
                case VARCHAR:
                case VARCHAR2:
                case NVARCHAR2:
                case VARSTRING:
                case NCHAR:
                    String rawValue = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    if (!getProtocol().isOracleMode()) {
                        return getDateFromString(rawValue, cal, columnInfo);
                    }
                    if ("0000-00-00".equals(rawValue)) {
                        lastValueNull |= BIT_LAST_ZERO_DATE;
                        return null;
                    }

                    try {
                        String regex = "^\\d{4}-\\d{2}-\\d{2}";
                        if (!rawValue.matches(regex + ".*")) {
                            throw new SQLException("bad format");
                        }
                        return Date.valueOf(rawValue.trim());
                    } catch (IllegalArgumentException illegalArgumentException) {
                        throw new SQLException("bad format, " + illegalArgumentException.getMessage());
                    } catch (Exception e) {
                        throw ExceptionFactory.INSTANCE.create("Could not get object as Date : "
                                                               + e.getMessage(), "S1009", e);
                    }
                default:
                    if (length == 0) {
                        if (options.zeroDateTimeBehavior.equalsIgnoreCase(ZERO_DATETIME_EXCEPTION)) {
                            if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                                throw new SQLException("Zero date value prohibited");
                            }
                            throw new SQLException("Value '0000-00-00' can not be represented as java.sql.Date");
                        }
                        lastValueNull |= BIT_LAST_FIELD_NULL;
                        return null;
                    }

                    int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);

                    if (length == 2 && columnInfo.getLength() == 2) {
                        // YEAR(2) - deprecated
                        if (year <= 69) {
                            year += 2000;
                        } else {
                            year += 1900;
                        }
                    }

                    if (columnInfo.getColumnType() == ColumnType.YEAR
                        && this.options.compatibleMysqlVersion == 8) {
                        if (year < 100) {
                            if (year <= 69) {
                                year += 100;
                            }
                            year += 1900;
                        }
                    }

                    int month = 1;
                    int day = 1;

                    if (length >= 4) {
                        month = buf[pos + 2];
                        day = buf[pos + 3];
                    }

                    Calendar calendar = getCalendarInstance(null);
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8 && !useCalLenientFlag) {
                        calendar.setLenient(false);
                    }
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8 && year == 0) {
                        yearIsZero = true;
                    } else {
                        yearIsZero = false;
                    }
                    calendar.clear();
                    calendar.set(Calendar.YEAR, year);
                    calendar.set(Calendar.MONTH, month - 1);
                    calendar.set(Calendar.DAY_OF_MONTH, day);
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    Date dt = null;
                    try {
                        dt = new Date(calendar.getTimeInMillis());
                    } finally {
                        if (useCalLenientFlag) {
                            useCalLenientFlag = false;
                        }
                        calendar.setLenient(true);
                    }
                    return dt;
            }
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new SQLException("bad format, " + illegalArgumentException.getMessage());
        } catch (SQLException sqlException) {
            throw sqlException; // don't re-wrap
        } catch (Exception e) {
            SQLException sqlException = new SQLException("Bad format for DATE "
                                                         + new String(buf, pos, length,
                                                             getCurrentEncoding(columnInfo
                                                                 .getColumnType())));
            sqlException.initCause(e);
            throw sqlException;
        }
    }

    /**
     * Get time from raw binary format.
     *
     * @param columnInfo column information
     * @param cal        calendar
     * @param timeZone   time zone
     * @return Time value
     * @throws SQLException if column cannot be converted to Time
     */
    public Time getInternalTime(ColumnDefinition columnInfo, Calendar cal, TimeZone timeZone)
                                                                                             throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (getProtocol().isOracleMode()) {
            if (columnInfo.getColumnType() == ColumnType.INTERVALYM || columnInfo.getColumnType() == ColumnType.INTERVALDS) {
                throw new SQLException("Invalid column type, getTime not implemented for this type.");
            }
        }
        switch (columnInfo.getColumnType()) {
            case TIMESTAMP:
            case TIMESTAMP_NANO:
            case DATETIME:
            case TIMESTAMP_TZ:
            case TIMESTAMP_LTZ:
                if (length == 0) {
                    return Time.valueOf("00:00:00");
                }
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    useCalLenientFlag = true;
                }
                Timestamp ts = getInternalTimestamp(columnInfo, cal, timeZone);
                return (ts == null) ? null : new Time(ts.getTime());
            case DATE:
                return Time.valueOf("00:00:00");
            case YEAR:
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    return Time.valueOf("00:00:00");
                }
                int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                if (year == 0) {
                    throw new SQLException("Bad format for Time '0001-01-01'");
                }
                String yearString = getInternalString(columnInfo, cal, timeZone);
                try {
                    return Time.valueOf(yearString);
                } catch (Exception e) {
                    throw new SQLException("Bad format for Time'" + yearString + "'");
                }
            case STRING:
            case NCHAR:
            case VARCHAR2:
            case NVARCHAR2:
                if (getProtocol().isOracleMode()) {
                    String raw = new String(buf, pos, length,
                            getCurrentEncoding(columnInfo.getColumnType()));
                    try {
                        return Time.valueOf(raw.trim());
                    } catch (IllegalArgumentException e) {
                        throw new SQLException("Time format \"" + raw
                                + "\" incorrect, must be HH:mm:ss");
                    }
                }
            default:
                ColumnType type = columnInfo.getColumnType();
                if (!getProtocol().isOracleMode()
                    && (type == ColumnType.STRING || type == ColumnType.VARCHAR || type == ColumnType.VARSTRING)) {
                    String raw = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    return getTimeFromString(raw, cal);
                }
                // convert string to a date
                Calendar calendar = getCalendarInstance(cal);
                calendar.clear();
                int day = 0;
                int hour = 0;
                int minutes = 0;
                int seconds = 0;
                boolean negate = false;
                if (length > 0) {
                    negate = (buf[pos] & 0xff) == 0x01;
                }
                if (length > 4) {
                    day = ((buf[pos + 1] & 0xff) + ((buf[pos + 2] & 0xff) << 8)
                           + ((buf[pos + 3] & 0xff) << 16) + ((buf[pos + 4] & 0xff) << 24));
                }
                if (length > 7) {
                    hour = buf[pos + 5];
                    minutes = buf[pos + 6];
                    seconds = buf[pos + 7];
                }
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    hour = day * 24 + hour;
                    if (hour < 0 || hour >= 24) {
                        throw new SQLException("The value '" + hour + ":" + minutes +":"+ seconds + "' is an invalid TIME value.");
                    }
                }
                calendar.set(1970, Calendar.JANUARY, ((negate ? -1 : 1) * day) + 1, (negate ? -1
                    : 1) * hour, minutes, seconds);

                int nanoseconds = 0;
                if (length > 8) {
                    nanoseconds = ((buf[pos + 8] & 0xff) + ((buf[pos + 9] & 0xff) << 8)
                                   + ((buf[pos + 10] & 0xff) << 16) + ((buf[pos + 11] & 0xff) << 24));
                }

                calendar.set(Calendar.MILLISECOND, nanoseconds / 1000);

                return new Time(calendar.getTimeInMillis());
        }
    }

    /**
     * Get timestamp from raw binary format.
     *
     * @param columnInfo   column information
     * @param userCalendar user calendar
     * @param timeZone     time zone
     * @return timestamp value
     * @throws SQLException if column type is not compatible
     */
    public Timestamp getInternalTimestamp(ColumnDefinition columnInfo, Calendar userCalendar,
                                          TimeZone timeZone) throws SQLException {
        try {
            if (lastValueWasNull()) {
                return null;
            }

            if (this.getProtocol().isOracleMode()) {
                if (columnInfo.getColumnType() == ColumnType.INTERVALYM || columnInfo.getColumnType() == ColumnType.INTERVALDS) {
                    throw new SQLException("Invalid column type, getTimestamp not implemented for this type.");
                }
                Calendar cal ;
                TIMESTAMP timestamp = null;
                switch (columnInfo.getColumnType()) {
                    case TIMESTAMP_TZ:
                        cal = getCalendarInstanceWithTimezone(userCalendar, timeZone);
                        TIMESTAMPTZ oracleTimestampZ = getInternalTIMESTAMPTZ(columnInfo,
                            userCalendar, timeZone);
                        timestamp = TIMESTAMPTZ.resultTIMESTAMP(getProtocol(),
                            oracleTimestampZ.toBytes());
                        return timestamp.timestampValue(cal);
                    case TIMESTAMP_LTZ:
                        cal = getCalendarInstanceWithTimezone(userCalendar, timeZone);
                        TIMESTAMPLTZ oracleTimestampLTZ = getInternalTIMESTAMPLTZ(columnInfo,
                            userCalendar, timeZone);
                        timestamp = TIMESTAMPLTZ.resultTIMESTAMP(getProtocol(),
                            oracleTimestampLTZ.getBytes());
                        return timestamp.timestampValue(cal);
                    case TIMESTAMP_NANO:
                        Calendar calWithoutTZ = getCalendarInstance(userCalendar);
                        return getInternalTIMESTAMP(columnInfo, userCalendar, TimeZone.getDefault())
                            .timestampValue(calWithoutTZ);
                    case VARCHAR2:
                    case NVARCHAR2:
                    case NCHAR:
                    case STRING:
                        try {
                            return Timestamp.valueOf(new String(buf, pos, length, getCurrentEncoding(columnInfo.getColumnType())).trim());
                        } catch (Exception e) {
                            throw new SQLException("bad format, " + e.getMessage());
                        }
                }
            }

            int year = 1970;
            int month = 0;
            int day = 0;
            int hour = 0;
            int minutes = 0;
            int seconds = 0;
            int microseconds = 0;
            int nanos = 0;

            switch (columnInfo.getColumnType()) {
                case TIME:
                    boolean negate = false;
                    if (length == 0) {
                       return buildTimestmap(year, 1, 1, hour, minutes, seconds, nanos, userCalendar, timeZone);
                    }
                    if (length > 0) {
                        negate = (buf[pos] & 0xff) == 0x01;
                    }
                    if (length > 4) {
                        day = ((buf[pos + 1] & 0xff) + ((buf[pos + 2] & 0xff) << 8)
                               + ((buf[pos + 3] & 0xff) << 16) + ((buf[pos + 4] & 0xff) << 24));
                    }
                    if (length > 7) {
                        hour = buf[pos + 5];
                        minutes = buf[pos + 6];
                        seconds = buf[pos + 7];
                    }
                    if (length > 8) {
                        microseconds = ((buf[pos + 8] & 0xff) + ((buf[pos + 9] & 0xff) << 8)
                                        + ((buf[pos + 10] & 0xff) << 16) + ((buf[pos + 11] & 0xff) << 24));
                        nanos = microseconds * 1000000;
                    }
                    year = 1970;
                    month = 1;
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                        hour = day * 24 + hour;
                        if (hour < 0 || hour >= 24) {
                            throw new SQLException("The value '" + hour + ":" + minutes +":"+ seconds + "' is an invalid TIME value.");
                        }
                    }
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 5) {
                        day = 1;
                    }else {
                        day = ((negate ? -1 : 1) * day) + 1;
                    }
                    hour = (negate ? -1 : 1) * hour;

                    break;

                case STRING:
                case VARSTRING:
                case NVARCHAR2:
                case VARCHAR2:
                case VARCHAR:
                    String rawValue = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    if (!getProtocol().isOracleMode()) {
                        return getTimestampFromString(columnInfo, rawValue, userCalendar, timeZone);
                    }
                    if (rawValue.startsWith("0000-00-00 00:00:00")) {
                        lastValueNull |= BIT_LAST_ZERO_DATE;
                        return null;
                    }
                    if (rawValue.length() >= 4) {
                        year = Integer.parseInt(rawValue.substring(0, 4));
                        if (rawValue.length() >= 7) {
                            month = Integer.parseInt(rawValue.substring(5, 7));
                            if (rawValue.length() >= 10) {
                                day = Integer.parseInt(rawValue.substring(8, 10));
                                if (rawValue.length() >= 19) {
                                    hour = Integer.parseInt(rawValue.substring(11, 13));
                                    minutes = Integer.parseInt(rawValue.substring(14, 16));
                                    seconds = Integer.parseInt(rawValue.substring(17, 19));
                                }
                                nanos = extractNanos(rawValue);
                            }
                        }
                    }
                    break;
                case YEAR:
                    year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                        if (year < 100) {
                            if (year <= 69) {
                                year += 100;
                            }
                            year += 1900;
                        }
                    }
                    return buildTimestmap(year, 1, 1, hour, minutes, seconds, nanos, userCalendar, timeZone);
                default:
                    if (length > 0) {
                        year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                        month = buf[pos + 2];
                        day = buf[pos + 3];

                        if (length > 4) {
                            hour = buf[pos + 4];
                            minutes = buf[pos + 5];
                            seconds = buf[pos + 6];

                            if (length > 7) {
                                microseconds = ((buf[pos + 7] & 0xff) + ((buf[pos + 8] & 0xff) << 8)
                                        + ((buf[pos + 9] & 0xff) << 16) + ((buf[pos + 10] & 0xff) << 24));
                                nanos = microseconds * 1000;
                            }
                        }
                    }
            }

            if (length == 0 || ((year == 0) && (month == 0) && (day == 0))) {
                if (options.zeroDateTimeBehavior.equalsIgnoreCase(ZERO_DATETIME_EXCEPTION)) {
                    if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                        throw new SQLException("Zero date value prohibited");
                    }
                    throw new SQLException(
                            "Value '0000-00-00' can not be represented as java.sql.Timestamp");
                }
                if (options.zeroDateTimeBehavior.equalsIgnoreCase(ZERO_DATETIME_CONVERT_TO_NULL)) {
                    return null;
                } else {
                    // round
                    year = 1;
                    month = 1;
                    day = 1;
                }
            }

            Calendar calendar = getCalendarInstance(userCalendar);
            Timestamp tt;
            if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8 && year == 0) {
                yearIsZero = true;
            } else {
                yearIsZero = false;
            }
            synchronized (calendar) {
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8 && !useCalLenientFlag) {
                    calendar.setLenient(false);
                }
                calendar.clear();
                calendar.set(year, month - 1, day, hour, minutes, seconds);
                try {
                    tt = new Timestamp(calendar.getTimeInMillis());
                } finally {
                    if (useCalLenientFlag) {
                        useCalLenientFlag = false;
                    }
                    calendar.setLenient(true);
                }
            }
            tt.setNanos(nanos);
            return tt;
        } catch (NumberFormatException e) {
            SQLException sqlException = new SQLException("Cannot convert value "
                                                         + new String(buf, pos, length,
                                                             getCurrentEncoding(columnInfo
                                                                 .getColumnType()))
                                                         + " to TIMESTAMP.");
            sqlException.initCause(e);
            throw sqlException;
        }
    }

    /**
     * Get Object from raw binary format.
     *
     * @param columnInfo column information
     * @param timeZone   time zone
     * @return Object value
     * @throws SQLException if column type is not compatible
     */
    public Object getInternalObject(ColumnDefinition columnInfo, TimeZone timeZone)
                                                                                   throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }

        switch (columnInfo.getColumnType()) {
            case BIT:
                if (columnInfo.getLength() == 1) {
                    return buf[pos] != 0;
                }
                byte[] dataBit = new byte[length];
                System.arraycopy(buf, pos, dataBit, 0, length);
                return dataBit;
            case TINYINT:
                if (options.tinyInt1isBit && columnInfo.getLength() == 1) {
                    return buf[pos] != 0;
                }
                return getInternalInt(columnInfo);
            case INTEGER:
                if (!columnInfo.isSigned()) {
                    return getInternalLong(columnInfo);
                }
                return getInternalInt(columnInfo);
            case BIGINT:
                if (!columnInfo.isSigned()) {
                    return getInternalBigInteger(columnInfo);
                }
                return getInternalLong(columnInfo);
            case DOUBLE:
            case BINARY_DOUBLE:
                return getInternalDouble(columnInfo);
            case VARCHAR:
            case VARCHAR2:
            case VARSTRING:
            case STRING:
            case ENUM:
                if (columnInfo.isBinary()) {
                    byte[] data = new byte[getLengthMaxFieldSize()];
                    System.arraycopy(buf, pos, data, 0, getLengthMaxFieldSize());
                    return data;
                }
                return getInternalString(columnInfo, null, timeZone);
            case TIMESTAMP:
            case TIMESTAMP_NANO:
            case DATETIME:
                if (columnInfo.getColumnType() == ColumnType.DATETIME && !this.options.mapDateToTimestamp && getProtocol().isOracleMode()) {
                    return getInternalDate(columnInfo, null, timeZone);
                }
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
                    useCalLenientFlag = true;
                }
                Timestamp timestamp = getInternalTimestamp(columnInfo, null, timeZone);
                if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8 && columnInfo.getColumnType() == ColumnType.DATETIME) {
                    LocalDateTime localDateTime = timestamp.toLocalDateTime();
                    if (yearIsZero) {
                        yearIsZero = false;
                        localDateTime = localDateTime.withYear(0);
                    }
                    return localDateTime;
                }
                return timestamp;
            case DATE:
                return getInternalDate(columnInfo, null, timeZone);
            case DECIMAL:
            case OBDECIMAL:
            case NUMBER_FLOAT:
                return getInternalBigDecimal(columnInfo);
            case BLOB:
            case LONGBLOB:
            case MEDIUMBLOB:
            case TINYBLOB:
                byte[] dataBlob = new byte[getLengthMaxFieldSize()];
                System.arraycopy(buf, pos, dataBlob, 0, getLengthMaxFieldSize());
                return dataBlob;
            case NULL:
                return null;
            case YEAR:
                if (options.yearIsDateType) {
                    return getInternalDate(columnInfo, null, timeZone);
                }
                return getInternalShort(columnInfo);
            case SMALLINT:
            case MEDIUMINT:
            case NUMBER:
                return getInternalInt(columnInfo);
            case FLOAT:
            case BINARY_FLOAT:
                return getInternalFloat(columnInfo);
            case TIME:
                return getInternalTime(columnInfo, null, timeZone);
            case OLDDECIMAL:
            case JSON:
                return getInternalString(columnInfo, null, timeZone);
            case GEOMETRY:
                byte[] data = new byte[length];
                System.arraycopy(buf, pos, data, 0, length);
                return data;
            case NEWDATE:
                break;
            case SET:
                break;
            case TIMESTAMP_TZ:
                return getInternalTIMESTAMPTZ(columnInfo, null, timeZone);
            case TIMESTAMP_LTZ:
                return getInternalTIMESTAMPLTZ(columnInfo, null, timeZone);
            case INTERVALYM:
                return getInternalINTERVALYM(columnInfo);
            case INTERVALDS:
                return getInternalINTERVALDS(columnInfo);
            case STRUCT: {
                byte[] structData = new byte[length];
                System.arraycopy(buf, pos, structData, 0, length);
                return structData;
            }
            //                return getInternalStruct(columnInfo);
            case ARRAY:
                byte[] arrayData = new byte[length];
                System.arraycopy(buf, pos, arrayData, 0, length);
                return arrayData;

                //                return getInternalArray(columnInfo);
            case CURSOR:
                //                return getInternalCursor(columnInfo,null,timeZone);
                return null;
                //                break;
            case RAW:
                byte[] returnBytes = new byte[length];
                System.arraycopy(buf, pos, returnBytes, 0, length);
                return returnBytes;
            case NVARCHAR2:
            case NCHAR:
                return getInternalString(columnInfo, null, timeZone);
            default:
                break;
        }
        throw ExceptionFactory.INSTANCE.notSupported(String.format("Type '%s' is not supported",
            columnInfo.getColumnType().getTypeName()));
    }

    /**
     * Get boolean from raw binary format.
     *
     * @param columnInfo column information
     * @return boolean value
     * @throws SQLException if column type doesn't permit conversion
     */
    public boolean getInternalBoolean(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return false;
        }
        if (columnInfo.getColumnType() == ColumnType.BIT) {
            return parseBit() != 0;
        }
        long boolVal = 0;
        switch (columnInfo.getColumnType()) {
            case TINYINT:
            case SMALLINT:
            case YEAR:
            case INTEGER:
            case MEDIUMINT:
            case BIGINT:
            case FLOAT:
            case DOUBLE:
            case DECIMAL:
            case OLDDECIMAL:
                boolVal = getInternalLong(columnInfo);
                return (boolVal > 0 || boolVal == -1);
            default:
                if (columnInfo.isBinary()) {
                    byte[] bytes = new byte[length];
                    System.arraycopy(buf, pos, bytes, 0, length);
                    return Utils.convertBytesToBoolean(bytes);
                } else {
                    final String rawVal = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    return Utils.convertStringToBoolean(rawVal);
                }
        }
    }

    /**
     * Get byte from raw binary format.
     *
     * @param columnInfo column information
     * @return byte value
     * @throws SQLException if column type doesn't permit conversion
     */
    public byte getInternalByte(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }
        long value;
        try {
            switch (columnInfo.getColumnType()) {
                case BIT:
                    value = parseBit();
                    break;
                case TINYINT:
                    value = getInternalTinyInt(columnInfo);
                    break;
                case SMALLINT:
                case YEAR:
                    value = getInternalSmallInt(columnInfo);
                    break;
                case INTEGER:
                case MEDIUMINT:
                    value = getInternalMediumInt(columnInfo);
                    break;
                case BIGINT:
                    value = getInternalLong(columnInfo);
                    break;
                case FLOAT:
                    value = (long) getInternalFloat(columnInfo);
                    break;
                case DOUBLE:
                    value = (long) getInternalDouble(columnInfo);
                    break;
                case BINARY_FLOAT:
                case BINARY_DOUBLE:
                    double internalDouble = getInternalDouble(columnInfo);
                    return (byte) internalDouble;
                case DECIMAL:
                case OLDDECIMAL:
                    BigDecimal bigDecimal = getInternalBigDecimal(columnInfo);
                    rangeCheck(Byte.class, Byte.MIN_VALUE, Byte.MAX_VALUE, bigDecimal, columnInfo);
                    return bigDecimal.byteValue();
                case VARSTRING:
                case VARCHAR:
                case VARCHAR2:
                case NVARCHAR2:
                case STRING:
                    if (length == 0) {
                        return (byte) convertToZeroWithEmptyCheck();
                    }
                    value = Long.parseLong(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                    break;
                case OBDECIMAL:
                    BigDecimal decimal = new BigDecimal(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                    decimal = decimal.setScale(0, RoundingMode.HALF_UP);
                    value = decimal.longValue();
                    break;
                case NUMBER_FLOAT:
                    BigDecimal numDecimal = new BigDecimal(new String(buf, pos, length,
                            getCurrentEncoding(columnInfo.getColumnType())).trim());
                    value = numDecimal.longValue();
                    break;
                default:
                    throw new SQLException("getByte not available for data field type "
                                           + columnInfo.getColumnType().getSqlTypeName());
            }
            rangeCheck(Byte.class, Byte.MIN_VALUE, Byte.MAX_VALUE, value, columnInfo);
            return (byte) value;
        } catch (NumberFormatException nfe) {
            // parse error.
            // if its a decimal retry without the decimal part.
            String valueParams = new String(buf, pos, length,
                getCurrentEncoding(columnInfo.getColumnType()));
            if (isIntegerRegex.matcher(valueParams).find()) {
                try {
                    value = Long.parseLong(valueParams.substring(0, valueParams.indexOf("."))
                        .trim());
                    rangeCheck(Byte.class, Byte.MIN_VALUE, Byte.MAX_VALUE, value, columnInfo);
                    return (byte) value;
                } catch (NumberFormatException nfee) {
                    // eat exception
                }
            }
            throw new SQLException("Out of range value for column '" + columnInfo.getName()
                                   + "' : value " + valueParams, "22003", 1264);
        }
    }

    /**
     * Get short from raw binary format.
     *
     * @param columnInfo column information
     * @return short value
     * @throws SQLException if column type doesn't permit conversion
     */
    public short getInternalShort(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return 0;
        }

        long value;
        try {
            switch (columnInfo.getColumnType()) {
                case BIT:
                    value = parseBit();
                    break;
                case TINYINT:
                    value = getInternalTinyInt(columnInfo);
                    break;
                case SMALLINT:
                case YEAR:
                    value = ((buf[pos] & 0xff) + ((buf[pos + 1] & 0xff) << 8));
                    if (columnInfo.isSigned()) {
                        return (short) value;
                    }
                    value = value & 0xffff;
                    break;
                case INTEGER:
                case MEDIUMINT:
                    value = getInternalMediumInt(columnInfo);
                    break;
                case BIGINT:
                    value = getInternalLong(columnInfo);
                    break;
                case FLOAT:
                    value = (long) getInternalFloat(columnInfo);
                    break;
                case DOUBLE:
                    value = (long) getInternalDouble(columnInfo);
                    break;
                case NUMBER_FLOAT:
                case DECIMAL:
                case OLDDECIMAL:
                    BigDecimal bigDecimal = getInternalBigDecimal(columnInfo);
                    rangeCheck(Short.class, Short.MIN_VALUE, Short.MAX_VALUE, bigDecimal,
                        columnInfo);
                    return bigDecimal.shortValue();
                case VARSTRING:
                case VARCHAR:
                case VARCHAR2:
                case NVARCHAR2:
                case STRING:
                    if (length == 0) {
                        return (short) convertToZeroWithEmptyCheck();
                    }
                    value = Long.parseLong(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                    break;
                case OBDECIMAL:
                    BigDecimal decimal = new BigDecimal(new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType())).trim());
                    decimal = decimal.setScale(0, RoundingMode.HALF_UP);
                    value = decimal.longValue();
                    break;
                case BINARY_FLOAT:
                case BINARY_DOUBLE:
                    double internalDouble = getInternalDouble(columnInfo);
                    return (short) internalDouble;
                default:
                    throw new SQLException("getShort not available for data field type "
                                           + columnInfo.getColumnType().getSqlTypeName());
            }
            rangeCheck(Short.class, Short.MIN_VALUE, Short.MAX_VALUE, value, columnInfo);
            return (short) value;
        } catch (NumberFormatException nfe) {
            // parse error.
            // if its a decimal retry without the decimal part.
            String valueParams = new String(buf, pos, length,
                getCurrentEncoding(columnInfo.getColumnType()));
            if (isIntegerRegex.matcher(valueParams).find()) {
                try {
                    value = Long.parseLong(valueParams.substring(0, valueParams.indexOf("."))
                        .trim());
                    rangeCheck(Short.class, Short.MIN_VALUE, Short.MAX_VALUE, value, columnInfo);
                    return (short) value;
                } catch (NumberFormatException nfee) {
                    // eat exception
                }
            }
            throw new SQLException("Out of range value for column '" + columnInfo.getName()
                                   + "' : value " + valueParams, "22003", 1264);
        }
    }

    /**
     * Get Time in string format from raw binary format.
     *
     * @param columnInfo column information
     * @return time value
     */
    public String getInternalTimeString(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            // binary send 00:00:00 as 0.
            if (columnInfo.getDecimals() == 0) {
                return "00:00:00";
            } else {
                StringBuilder value = new StringBuilder("00:00:00.");
                int decimal = columnInfo.getDecimals();
                while (decimal-- > 0) {
                    value.append("0");
                }
                return value.toString();
            }
        }

        String rawValue = new String(buf, pos, length,
            getCurrentEncoding(columnInfo.getColumnType()));
        if ("0000-00-00".equals(rawValue)) {
            return null;
        }

        int day = ((buf[pos + 1] & 0xff) | ((buf[pos + 2] & 0xff) << 8)
                   | ((buf[pos + 3] & 0xff) << 16) | ((buf[pos + 4] & 0xff) << 24));
        int hour = buf[pos + 5];
        int timeHour = hour;
        if (!getProtocol().isOracleMode() && options.compatibleMysqlVersion == 8) {
            timeHour = hour + day * 24;
        }

        String hourString;
        if (timeHour < 10) {
            hourString = "0" + timeHour;
        } else {
            hourString = Integer.toString(timeHour);
        }

        String minuteString;
        int minutes = buf[pos + 6];
        if (minutes < 10) {
            minuteString = "0" + minutes;
        } else {
            minuteString = Integer.toString(minutes);
        }

        String secondString;
        int seconds = buf[pos + 7];
        if (seconds < 10) {
            secondString = "0" + seconds;
        } else {
            secondString = Integer.toString(seconds);
        }

        int microseconds = 0;
        StringBuilder microsecondString = null;
        if (length > 8) {
            microseconds = ((buf[pos + 8] & 0xff) | (buf[pos + 9] & 0xff) << 8
                            | (buf[pos + 10] & 0xff) << 16 | (buf[pos + 11] & 0xff) << 24);
            if (getProtocol().isOracleMode()) {
                microsecondString = new StringBuilder(Integer.toString(microseconds));
                while (microsecondString.length() < 6) {
                    microsecondString.insert(0, "0");
                }
            } else if (options.compatibleMysqlVersion == 8) {
                BigDecimal bigDecimal = (new BigDecimal(microseconds)).divide(
                    new BigDecimal(1000 * 1000)).stripTrailingZeros();
                String decimalPart = bigDecimal.toPlainString().split("\\.")[1];

                if (!decimalPart.matches("[0]+")) {
                    microsecondString = new StringBuilder(decimalPart);
                    for (int i = decimalPart.length(); i < columnInfo.getPrimitiveDecimals(); i++) {
                        microsecondString.append('0');
                    }
                }
            }
        }

        boolean negative = (buf[pos] == 0x01);
        return (negative ? "-" : "") + hourString + ":" + minuteString + ":" + secondString
               + (microsecondString == null ? "" : ("." + microsecondString));
    }

    /**
     * Get BigInteger from raw binary format.
     *
     * @param columnInfo column information
     * @return BigInteger value
     * @throws SQLException if column type doesn't permit conversion or value is not in BigInteger
     *                      range
     */
    public BigInteger getInternalBigInteger(ColumnDefinition columnInfo) throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        switch (columnInfo.getColumnType()) {
            case BIT:
                return BigInteger.valueOf(buf[pos]);
            case TINYINT:
                return BigInteger.valueOf(columnInfo.isSigned() ? buf[pos] : (buf[pos] & 0xff));
            case SMALLINT:
            case YEAR:
                short valueShort = (short) ((buf[pos] & 0xff) | ((buf[pos + 1] & 0xff) << 8));
                return BigInteger.valueOf(columnInfo.isSigned() ? valueShort
                    : (valueShort & 0xffff));
            case INTEGER:
            case MEDIUMINT:
                int valueInt = ((buf[pos] & 0xff) + ((buf[pos + 1] & 0xff) << 8)
                                + ((buf[pos + 2] & 0xff) << 16) + ((buf[pos + 3] & 0xff) << 24));
                return BigInteger.valueOf(((columnInfo.isSigned()) ? valueInt
                    : (valueInt >= 0) ? valueInt : valueInt & 0xffffffffL));
            case BIGINT:
                long value = ((buf[pos] & 0xff) + ((long) (buf[pos + 1] & 0xff) << 8)
                              + ((long) (buf[pos + 2] & 0xff) << 16)
                              + ((long) (buf[pos + 3] & 0xff) << 24)
                              + ((long) (buf[pos + 4] & 0xff) << 32)
                              + ((long) (buf[pos + 5] & 0xff) << 40)
                              + ((long) (buf[pos + 6] & 0xff) << 48) + ((long) (buf[pos + 7] & 0xff) << 56));
                if (columnInfo.isSigned()) {
                    return BigInteger.valueOf(value);
                } else {
                    return new BigInteger(1, new byte[] { (byte) (value >> 56),
                            (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
                            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8),
                            (byte) value });
                }
            case FLOAT:
                return BigInteger.valueOf((long) getInternalFloat(columnInfo));
            case DOUBLE:
                return BigInteger.valueOf((long) getInternalDouble(columnInfo));
            case DECIMAL:
            case OBDECIMAL:
            case OLDDECIMAL:
                return BigInteger.valueOf(getInternalBigDecimal(columnInfo).longValue());
            default:
                return new BigInteger(new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType())).trim());
        }
    }

    /**
     * Get ZonedDateTime from raw binary format.
     *
     * @param columnInfo column information
     * @param clazz      asked class
     * @param timeZone   time zone
     * @return ZonedDateTime value
     * @throws SQLException if column type doesn't permit conversion
     */
    public ZonedDateTime getInternalZonedDateTime(ColumnDefinition columnInfo, Class clazz,
                                                  TimeZone timeZone) throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            lastValueNull |= BIT_LAST_FIELD_NULL;
            return null;
        }
        if (getProtocol().isOracleMode()) {
            if (columnInfo.getColumnType() == ColumnType.TIMESTAMP_NANO
                || columnInfo.getColumnType() == ColumnType.TIMESTAMP_TZ
                || columnInfo.getColumnType() == ColumnType.TIMESTAMP_LTZ) {
                Timestamp oracleTimestamp = getInternalTimestamp(columnInfo, null, timeZone);
                if (oracleTimestamp == null) {
                    return null;
                }
                LocalDateTime localDateTimeNoTimeZone = oracleTimestamp.toLocalDateTime();
                return localDateTimeNoTimeZone.atZone(timeZone.toZoneId());
            }
        }
        switch (columnInfo.getColumnType().getSqlType()) {
            case Types.TIMESTAMP:
            case Types.DATE:
                int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                int month = buf[pos + 2];
                int day = buf[pos + 3];
                int hour = 0;
                int minutes = 0;
                int seconds = 0;
                int microseconds = 0;

                if (length > 4) {
                    hour = buf[pos + 4];
                    minutes = buf[pos + 5];
                    seconds = buf[pos + 6];

                    if (length > 7) {
                        microseconds = ((buf[pos + 7] & 0xff) + ((buf[pos + 8] & 0xff) << 8)
                                        + ((buf[pos + 9] & 0xff) << 16) + ((buf[pos + 10] & 0xff) << 24));
                    }
                }

                return ZonedDateTime.of(year, month, day, hour, minutes, seconds,
                    microseconds * 1000, timeZone.toZoneId());

            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CHAR:
                // string conversion
                String raw = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                if (raw.startsWith("0000-00-00 00:00:00")) {
                    return null;
                }
                try {
                    return ZonedDateTime.parse(raw, TEXT_ZONED_DATE_TIME);
                } catch (DateTimeParseException dateParserEx) {
                    throw new SQLException(
                        raw
                                + " cannot be parse as ZonedDateTime. time must have \"yyyy-MM-dd[T/ ]HH:mm:ss[.S]\" "
                                + "with offset and timezone format (example : '2011-12-03 10:15:30+01:00[Europe/Paris]')");
                }

            default:
                throw new SQLException("Cannot read " + clazz.getName() + " using a "
                                       + columnInfo.getColumnType().getSqlTypeName() + " field");
        }
    }

    /**
     * Get OffsetTime from raw binary format.
     *
     * @param columnInfo column information
     * @param timeZone   time zone
     * @return OffsetTime value
     * @throws SQLException if column type doesn't permit conversion
     */
    public OffsetTime getInternalOffsetTime(ColumnDefinition columnInfo, TimeZone timeZone)
                                                                                           throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            lastValueNull |= BIT_LAST_FIELD_NULL;
            return null;
        }

        ZoneId zoneId = timeZone.toZoneId().normalized();
        if (zoneId instanceof ZoneOffset) {
            ZoneOffset zoneOffset = (ZoneOffset) zoneId;

            int day = 0;
            int hour = 0;
            int minutes = 0;
            int seconds = 0;
            int microseconds = 0;

            switch (columnInfo.getColumnType().getSqlType()) {
                case Types.TIMESTAMP:
                    int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                    int month = buf[pos + 2];
                    day = buf[pos + 3];

                    if (length > 4) {
                        hour = buf[pos + 4];
                        minutes = buf[pos + 5];
                        seconds = buf[pos + 6];

                        if (length > 7) {
                            microseconds = ((buf[pos + 7] & 0xff) + ((buf[pos + 8] & 0xff) << 8)
                                            + ((buf[pos + 9] & 0xff) << 16) + ((buf[pos + 10] & 0xff) << 24));
                        }
                    }

                    return ZonedDateTime
                        .of(year, month, day, hour, minutes, seconds, microseconds * 1000,
                            zoneOffset).toOffsetDateTime().toOffsetTime();

                case Types.TIME:
                    final boolean negate = (buf[pos] & 0xff) == 0x01;

                    if (length > 4) {
                        day = ((buf[pos + 1] & 0xff) + ((buf[pos + 2] & 0xff) << 8)
                               + ((buf[pos + 3] & 0xff) << 16) + ((buf[pos + 4] & 0xff) << 24));
                    }

                    if (length > 7) {
                        hour = buf[pos + 5];
                        minutes = buf[pos + 6];
                        seconds = buf[pos + 7];
                    }

                    if (length > 8) {
                        microseconds = ((buf[pos + 8] & 0xff) + ((buf[pos + 9] & 0xff) << 8)
                                        + ((buf[pos + 10] & 0xff) << 16) + ((buf[pos + 11] & 0xff) << 24));
                    }

                    return OffsetTime.of((negate ? -1 : 1) * (day * 24 + hour), minutes, seconds,
                        microseconds * 1000, zoneOffset);

                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CHAR:
                    String raw = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    try {
                        return OffsetTime.parse(raw, DateTimeFormatter.ISO_OFFSET_TIME);
                    } catch (DateTimeParseException dateParserEx) {
                        throw new SQLException(
                            raw
                                    + " cannot be parse as OffsetTime (format is \"HH:mm:ss[.S]\" with offset for data type \""
                                    + columnInfo.getColumnType() + "\")");
                    }

                default:
                    throw new SQLException("Cannot read " + OffsetTime.class.getName()
                                           + " using a "
                                           + columnInfo.getColumnType().getSqlTypeName() + " field");
            }
        }

        //zoneId instanceof ZoneRegion
        if (!getProtocol().isOracleMode()) {
            switch (columnInfo.getColumnType().getSqlType()) {
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CHAR:
                    String raw = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    try {
                        return OffsetTime.parse(raw, DateTimeFormatter.ISO_OFFSET_TIME);
                    } catch (DateTimeParseException dateParserEx) {
                        throw new SQLException(
                            raw
                                    + " cannot be parse as OffsetTime (format is \"HH:mm:ss[.S]\" with offset for data type \""
                                    + columnInfo.getColumnType() + "\")");
                    }
            }
        }

        if (options.useLegacyDatetimeCode) {
            // system timezone is not an offset
            throw new SQLException(
                "Cannot return an OffsetTime for a TIME field when default timezone is '"
                        + zoneId
                        + "' (only possible for time-zone offset from Greenwich/UTC, such as +02:00)");
        }

        // server timezone is not an offset
        throw new SQLException(
            "Cannot return an OffsetTime for a TIME field when server timezone '" + zoneId
                    + "' (only possible for time-zone offset from Greenwich/UTC, such as +02:00)");
    }

    public OffsetDateTime getInternalOffsetDateTime(ColumnDefinition columnInfo, TimeZone timeZone)
                                                                                                   throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            lastValueNull |= BIT_LAST_FIELD_NULL;
            return null;
        }

        ZoneId zoneId = timeZone.toZoneId().normalized();
        if (zoneId instanceof ZoneOffset) {
            ZoneOffset zoneOffset = (ZoneOffset) zoneId;

            int day = 0;
            int hour = 0;
            int minutes = 0;
            int seconds = 0;
            int microseconds = 0;

            switch (columnInfo.getColumnType().getSqlType()) {
                case Types.TIMESTAMP:
                    int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                    int month = buf[pos + 2];
                    day = buf[pos + 3];

                    if (length > 4) {
                        hour = buf[pos + 4];
                        minutes = buf[pos + 5];
                        seconds = buf[pos + 6];

                        if (length > 7) {
                            microseconds = ((buf[pos + 7] & 0xff) + ((buf[pos + 8] & 0xff) << 8)
                                            + ((buf[pos + 9] & 0xff) << 16) + ((buf[pos + 10] & 0xff) << 24));
                        }
                    }

                    return ZonedDateTime.of(year, month, day, hour, minutes, seconds,
                        microseconds * 1000, zoneOffset).toOffsetDateTime();

                    //                case Types.TIME:
                    //                    final boolean negate = (buf[pos] & 0xff) == 0x01;
                    //
                    //                    if (length > 4) {
                    //                        day = ((buf[pos + 1] & 0xff) + ((buf[pos + 2] & 0xff) << 8)
                    //                                + ((buf[pos + 3] & 0xff) << 16) + ((buf[pos + 4] & 0xff) << 24));
                    //                    }
                    //
                    //                    if (length > 7) {
                    //                        hour = buf[pos + 5];
                    //                        minutes = buf[pos + 6];
                    //                        seconds = buf[pos + 7];
                    //                    }
                    //
                    //                    if (length > 8) {
                    //                        microseconds = ((buf[pos + 8] & 0xff) + ((buf[pos + 9] & 0xff) << 8)
                    //                                + ((buf[pos + 10] & 0xff) << 16) + ((buf[pos + 11] & 0xff) << 24));
                    //                    }
                    //
                    //                    return OffsetDateTime.of((negate ? -1 : 1) * (day * 24 + hour), minutes, seconds,
                    //                            microseconds * 1000, zoneOffset);

                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CHAR:
                    String raw = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    try {
                        return OffsetDateTime.parse(raw.replace(" ", "T"));
                    } catch (DateTimeParseException dateParserEx) {
                        throw new SQLException(
                            raw
                                    + " cannot be parse as OffsetTime (format is \"HH:mm:ss[.S]\" with offset for data type \""
                                    + columnInfo.getColumnType() + "\")");
                    }

                default:
                    throw new SQLException("Cannot read " + OffsetTime.class.getName()
                                           + " using a "
                                           + columnInfo.getColumnType().getSqlTypeName() + " field");
            }
        }

        //zoneId instanceof ZoneRegion
        if (!getProtocol().isOracleMode()) {
            switch (columnInfo.getColumnType().getSqlType()) {
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CHAR:
                    String raw = new String(buf, pos, length,
                        getCurrentEncoding(columnInfo.getColumnType()));
                    try {
                        return OffsetDateTime.parse(raw.replace(" ", "T"));
                    } catch (DateTimeParseException dateParserEx) {
                        throw new SQLException(
                            raw
                                    + " cannot be parse as OffsetTime (format is \"HH:mm:ss[.S]\" with offset for data type \""
                                    + columnInfo.getColumnType() + "\")");
                    }
            }
        }

        if (options.useLegacyDatetimeCode) {
            // system timezone is not an offset
            throw new SQLException(
                "Cannot return an OffsetTime for a TIME field when default timezone is '"
                        + zoneId
                        + "' (only possible for time-zone offset from Greenwich/UTC, such as +02:00)");
        }

        // server timezone is not an offset
        throw new SQLException(
            "Cannot return an OffsetTime for a TIME field when server timezone '" + zoneId
                    + "' (only possible for time-zone offset from Greenwich/UTC, such as +02:00)");
    }

    /**
     * Get LocalTime from raw binary format.
     *
     * @param columnInfo column information
     * @param timeZone   time zone
     * @return LocalTime value
     * @throws SQLException if column type doesn't permit conversion
     */
    public LocalTime getInternalLocalTime(ColumnDefinition columnInfo, TimeZone timeZone)
                                                                                         throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            lastValueNull |= BIT_LAST_FIELD_NULL;
            return null;
        }

        switch (columnInfo.getColumnType().getSqlType()) {
            case Types.TIME:
                int day = 0;
                int hour = 0;
                int minutes = 0;
                int seconds = 0;
                int microseconds = 0;

                final boolean negate = (buf[pos] & 0xff) == 0x01;

                if (length > 4) {
                    day = ((buf[pos + 1] & 0xff) + ((buf[pos + 2] & 0xff) << 8)
                           + ((buf[pos + 3] & 0xff) << 16) + ((buf[pos + 4] & 0xff) << 24));
                }

                if (length > 7) {
                    hour = buf[pos + 5];
                    minutes = buf[pos + 6];
                    seconds = buf[pos + 7];
                }

                if (length > 8) {
                    microseconds = ((buf[pos + 8] & 0xff) + ((buf[pos + 9] & 0xff) << 8)
                                    + ((buf[pos + 10] & 0xff) << 16) + ((buf[pos + 11] & 0xff) << 24));
                }

                return LocalTime.of((negate ? -1 : 1) * (day * 24 + hour), minutes, seconds,
                    microseconds * 1000);

            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CHAR:
                // string conversion
                String raw = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                try {
                    return LocalTime.parse(raw,
                        DateTimeFormatter.ISO_LOCAL_TIME.withZone(timeZone.toZoneId()));
                } catch (DateTimeParseException dateParserEx) {
                    throw new SQLException(
                        raw
                                + " cannot be parse as LocalTime (format is \"HH:mm:ss[.S]\" for data type \""
                                + columnInfo.getColumnType() + "\")");
                }

            case Types.TIMESTAMP:
                ZonedDateTime zonedDateTime = getInternalZonedDateTime(columnInfo, LocalTime.class,
                    timeZone);
                return zonedDateTime == null ? null : zonedDateTime.withZoneSameInstant(
                    ZoneId.systemDefault()).toLocalTime();

            default:
                throw new SQLException("Cannot read LocalTime using a "
                                       + columnInfo.getColumnType().getSqlTypeName() + " field");
        }
    }

    /**
     * Get LocalDate from raw binary format.
     *
     * @param columnInfo column information
     * @param timeZone   time zone
     * @return LocalDate value
     * @throws SQLException if column type doesn't permit conversion
     */
    public LocalDate getInternalLocalDate(ColumnDefinition columnInfo, TimeZone timeZone)
                                                                                         throws SQLException {
        if (lastValueWasNull()) {
            return null;
        }
        if (length == 0) {
            lastValueNull |= BIT_LAST_FIELD_NULL;
            return null;
        }

        switch (columnInfo.getColumnType().getSqlType()) {
            case Types.DATE:
                int year = ((buf[pos] & 0xff) | (buf[pos + 1] & 0xff) << 8);
                int month = buf[pos + 2];
                int day = buf[pos + 3];
                return LocalDate.of(year, month, day);

            case Types.TIMESTAMP:
                ZonedDateTime zonedDateTime = getInternalZonedDateTime(columnInfo, LocalDate.class,
                    timeZone);
                return zonedDateTime == null ? null : zonedDateTime.withZoneSameInstant(
                    ZoneId.systemDefault()).toLocalDate();

            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.CHAR:
                // string conversion
                String raw = new String(buf, pos, length,
                    getCurrentEncoding(columnInfo.getColumnType()));
                if (raw.startsWith("0000-00-00")) {
                    return null;
                }
                try {
                    return LocalDate.parse(raw,
                        DateTimeFormatter.ISO_LOCAL_DATE.withZone(timeZone.toZoneId()));
                } catch (DateTimeParseException dateParserEx) {
                    throw new SQLException(
                        raw + " cannot be parse as LocalDate. time must have \"yyyy-MM-dd\" format");
                }

            default:
                throw new SQLException("Cannot read LocalDate using a "
                                       + columnInfo.getColumnType().getSqlTypeName() + " field");
        }
    }

    private ComplexData getComplexField(Buffer packet, ComplexDataType type, Connection connection)
                                                                                                   throws SQLException {
        ComplexData value = null;
        if (null == type || !type.isValid()) {
            throw new SQLException(String.format(
                "invalid complex type, check if exists, typeName=%s", type.getTypeName()));
        }
        switch (type.getType()) {
            case ComplexDataType.TYPE_COLLECTION:
                value = getComplexArray(packet, type, connection);
                break;
            case ComplexDataType.TYPE_OBJECT:
                value = getComplexStruct(packet, type, connection);
                break;
            default:
                throw new SQLException(String.format(
                    "invalid complex type, check if exists, typeName=%s", type.getTypeName()));
        }
        return value;
    }

    private ComplexData getComplexArray(Buffer packet, ComplexDataType type, Connection connection)
                                                                                                   throws SQLException {
        ComplexData array = new ArrayImpl(type);
        int attrCount = (int) packet.readFieldLength();
        array.setAttrCount(attrCount);
        int curPos = packet.getPosition();
        // according https://dev.mysql.com/doc/internals/en/null-bitmap.html
        // result offset is 2
        byte[] nullBitsBuffer = packet.getBytes(curPos, (attrCount + 7 + 2) / 8);
        packet.setPosition(curPos + (attrCount + 7 + 2) / 8);
        for (int i = 0; i < attrCount; ++i) {
            if ((nullBitsBuffer[(i + 2) / 8] & (1 << ((i + 2) % 8))) == 0) {
                Object value = getComplexAttrData(packet, type.getAttrType(0), connection);
                array.addAttrData(i, value);
            } else {
                array.addAttrData(i, null);
                // nullmap pos is null
            }
        }
        return array;
    }

    public ComplexData getComplexStruct(Buffer packet, ComplexDataType type, Connection connection)
                                                                                                   throws SQLException {
        ComplexData struct = new StructImpl(type);
        int attrCount = type.getAttrCount();
        struct.setAttrCount(attrCount);
        int curPos = packet.getPosition();
        byte[] nullBitsBuffer = packet.getBytes(curPos, (attrCount + 7 + 2) / 8);
        packet.setPosition(curPos + (attrCount + 7 + 2) / 8);
        for (int i = 0; i < attrCount; ++i) {
            if ((nullBitsBuffer[(i + 2) / 8] & (1 << ((i + 2) % 8))) == 0) {
                Object value = getComplexAttrData(packet, type.getAttrType(i), connection);
                struct.addAttrData(i, value);
            } else {
                struct.addAttrData(i, null);
                // nullmap pos is null
            }
        }
        return struct;
    }

    private java.sql.Timestamp getComplexDate(byte[] bits) {
        int year = 0;
        int month = 0;
        int day = 0;

        int hour = 0;
        int minute = 0;
        int seconds = 0;

        int nanos = 0;
        if (null == bits) {
            return null;
        }
        int length = bits.length;
        if (length != 0) {
            year = (bits[0] & 0xff) | ((bits[1] & 0xff) << 8);
            month = bits[2];
            day = bits[3];

            if (length > 4) {
                hour = bits[4];
                minute = bits[5];
                seconds = bits[6];
            }

            if (length > 7) {
                // MySQL uses microseconds
                nanos = ((bits[7] & 0xff) | ((bits[8] & 0xff) << 8) | ((bits[9] & 0xff) << 16) | ((bits[10] & 0xff) << 24)) * 1000;
            }
        }
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, hour, minute, seconds);

        long tsAsMillis = cal.getTimeInMillis();

        Timestamp ts = new Timestamp(tsAsMillis);
        ts.setNanos(nanos);
        return ts;
    }

    private java.sql.Timestamp getComplexTimestamp(byte[] bits) throws SQLException {
        TIMESTAMP timestamp = buildTIMETAMP(bits, 0, bits.length);
        return timestamp.timestampValue();
    }

    private Object getComplexAttrData(Buffer packet, ComplexDataType type, Connection connection)
                                                                                                 throws SQLException {
        Object value = null;
        byte[] b = null;
        Charset charset = Charset.forName(this.getProtocol().getEncoding());
        switch (type.getType()) {
            case ComplexDataType.TYPE_NUMBER:
                b = packet.readLenByteArray(0);
                value = new BigDecimal(new String(b, 0, b.length, StandardCharsets.UTF_8).trim());
                // number type use UTF-8 to convert
                break;
            case ComplexDataType.TYPE_VARCHAR2:
            case ComplexDataType.TYPE_CHAR:
                b = packet.readLenByteArray(0);
                value = new String(b, 0, b.length, charset);
                break;
            case ComplexDataType.TYPE_RAW:
                b = packet.readLenByteArray(0);
                value = b;
                break;
            case ComplexDataType.TYPE_DATE:
                value = getComplexDate(packet.readLenByteArray(0));
                break;
            case ComplexDataType.TYPE_TIMESTMAP:
                value = getComplexTimestamp(packet.readLenByteArray(0));
                break;
            case ComplexDataType.TYPE_COLLECTION:
                value = getComplexArray(packet, type, connection);
                break;
            case ComplexDataType.TYPE_OBJECT:
                value = getComplexStruct(packet, type, connection);
                break;
            case ComplexDataType.TYPE_CLOB:
                b = packet.readLenByteArray(0);
                if (options.supportLobLocator) {
                    Clob c = new com.oceanbase.jdbc.Clob(true, b, charset.name(),
                        (OceanBaseConnection) connection);
                    value = c.getSubString(1L, (int) c.length());
                    c.free();
                } else {
                    return new String(buf, pos, length, charset);
                }
                break;
            case ComplexDataType.TYPE_BLOB:
                b = packet.readLenByteArray(0);
                if (options.supportLobLocator) {
                    Blob blob = new com.oceanbase.jdbc.Blob(true, b, charset.name(),
                        (OceanBaseConnection) connection);
                    value = blob.getBytes(1, (int) blob.length());
                    blob.free();
                } else {
                    value = b;
                }
                break;
            default:
                throw new SQLException("unsupported complex data type");
        }
        return value;
    }

    /**
     * @param columnInf
     * @param complexDataType
     * @return
     * @throws SQLException
     */
    public Array getInternalArray(ColumnDefinition columnInf, ComplexDataType complexDataType,
                                  Connection connection) throws SQLException {
        Array ret = null;
        Buffer buffer = new Buffer(buf);
        buffer.setPosition(pos);
        ret = (ObArray) getComplexField(buffer, complexDataType, connection);
        pos = buffer.getPosition();
        return ret;
    }

    /**
     * Get Struct from raw binary format.
     *
     * @param columnInfo column information
     * @return struct value
     * @throws SQLException exception
     */
    public Struct getInternalStruct(ColumnDefinition columnInfo, ComplexDataType complexDataType,
                                    Connection connection) throws SQLException {
        ObStruct struct = null;
        Buffer buffer = new Buffer(buf);
        buffer.setPosition(pos);
        struct = (ObStruct) getComplexField(buffer, complexDataType, connection);
        pos = buffer.getPosition();
        return struct;
    }

    public ComplexData getInternalComplexCursor(ColumnDefinition columnInfo,
                                                ComplexDataType complexDataType,
                                                Connection connection) throws SQLException {

        ComplexData value = new ComplexData(complexDataType);
        if (buf.length <= pos) {
            throw new SQLException("cursor is not open"); //Pretend the cursor is closed
        }

        Buffer buffer = new Buffer(buf);
        buffer.setPosition(pos);
        int id = (int) buffer.readLong4BytesV1();
        value.setAttrCount(1);
        RowObCursorData rowObCursorData = new RowObCursorData(id, true);
        value.addAttrData(0, rowObCursorData);
        pos = buffer.getPosition();
        return value;

    }

    @Override
    public INTERVALDS getInternalINTERVALDS(ColumnDefinition columnInfo) throws SQLException {
        if (columnInfo.getColumnType() != ColumnType.INTERVALDS) {
            throw new SQLException("the field type is not FIELD_TYPE_INTERVALDS");
        }
        byte[] target = new byte[length];
        System.arraycopy(buf, pos, target, 0, length);
        return new INTERVALDS(target);
    }

    @Override
    public INTERVALYM getInternalINTERVALYM(ColumnDefinition columnInfo) throws SQLException {
        if (columnInfo.getColumnType() != ColumnType.INTERVALYM) {
            throw new SQLException("the field type is not FIELD_TYPE_INTERVALYM");
        }
        byte[] target = new byte[length];
        System.arraycopy(buf, pos, target, 0, length);
        return new INTERVALYM(target);
    }

    /**
     * Indicate if data is binary encoded.
     *
     * @return always true.
     */
    public boolean isBinaryEncoded() {
        return true;
    }

    private BigDecimal setScaleOrHandleException(BigDecimal decimal, int scale) throws SQLException {
        try {
            return decimal.setScale(scale);
        } catch (ArithmeticException ex) {
            try {
                return decimal.setScale(scale, RoundingMode.HALF_UP);
            } catch (ArithmeticException e) {
                throw new SQLException("ResultSet.Bad_format_for_BigDecimal");
            }
        }
    }
}
