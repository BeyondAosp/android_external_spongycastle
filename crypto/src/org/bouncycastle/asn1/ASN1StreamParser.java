package org.bouncycastle.asn1;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class ASN1StreamParser
{
    private final InputStream _in;
    private final int         _limit;

    public ASN1StreamParser(
        InputStream in)
    {
        this(in, Integer.MAX_VALUE);
    }

    public ASN1StreamParser(
        InputStream in,
        int         limit)
    {
        this._in = in;
        this._limit = limit;
    }

    public ASN1StreamParser(
        byte[] encoding)
    {
        this(new ByteArrayInputStream(encoding), encoding.length);
    }

    private int readLength()
        throws IOException
    {
        int length = _in.read();
        if (length < 0)
        {
            throw new EOFException("EOF found when length expected");
        }

        if (length == 0x80)
        {
            return -1;      // indefinite-length encoding
        }

        if (length > 127)
        {
            int size = length & 0x7f;

            if (size > 4)
            {
                throw new IOException("DER length more than 4 bytes");
            }

            length = 0;
            for (int i = 0; i < size; i++)
            {
                int next = _in.read();

                if (next < 0)
                {
                    throw new EOFException("EOF found reading length");
                }

                length = (length << 8) + next;
            }

            if (length < 0)
            {
                throw new IOException("corrupted stream - negative length found");
            }

            if (length >= _limit)   // after all we must have read at least 1 byte
            {
                throw new IOException("corrupted stream - out of bounds length found");
            }
        }

        return length;
    }

    public DEREncodable readObject()
        throws IOException
    {
        int tag = _in.read();
        if (tag == -1)
        {
            return null;
        }

        //
        // turn of looking for "00" while we resolve the tag
        //
        set00Check(false);

        //
        // calculate tag number
        //
        int tagNo = 0;
        if ((tag & DERTags.TAGGED) != 0 || (tag & DERTags.APPLICATION) != 0)
        {
            tagNo = readTagNumber(tag);
        }

        boolean isConstructed = (tag & DERTags.CONSTRUCTED) != 0;
        int baseTagNo = tag & ~DERTags.CONSTRUCTED;

        //
        // calculate length
        //
        int length = readLength();

        if (length < 0) // indefinite length method
        {
            // TODO Verify that the tag is constructed?

            IndefiniteLengthInputStream indIn = new IndefiniteLengthInputStream(_in);

            if ((tag & DERTags.TAGGED) != 0)
            {
                return new BERTaggedObjectParser(tag, tagNo, indIn);
            }

            switch (baseTagNo)
            {
                // NULL should always be primitive (therefore definite length encoded)
                case DERTags.NULL:
                    while (indIn.read() >= 0)
                    {
                        // make sure we skip to end of object
                    }
                    return BERNull.INSTANCE;
                case DERTags.OCTET_STRING:
                    return new BEROctetStringParser(new ASN1StreamParser(indIn));
                case DERTags.SEQUENCE:
                    return new BERSequenceParser(new ASN1StreamParser(indIn));
                case DERTags.SET:
                    return new BERSetParser(new ASN1StreamParser(indIn));
                default:
                    throw new IOException("unknown BER object encountered");
            }
        }
        else
        {
            DefiniteLengthInputStream defIn = new DefiniteLengthInputStream(_in, length);

            if ((tag & DERTags.APPLICATION) != 0)
            {
                return new DERApplicationSpecific(tagNo, defIn.toByteArray());
            }

            if ((tag & DERTags.TAGGED) != 0)
            {
                return new BERTaggedObjectParser(tag, tagNo, defIn);
            }

            // TODO This code should be more aware of constructed vs. primitive encodings
            
            switch (baseTagNo)
            {
                case DERTags.BIT_STRING:
                {
                    byte[] bytes = defIn.toByteArray();                
                    int     padBits = bytes[0];
                    byte[]  data = new byte[bytes.length - 1];

                    System.arraycopy(bytes, 1, data, 0, bytes.length - 1);

                    return new DERBitString(data, padBits);
                }
                case DERTags.BMP_STRING:
                    return new DERBMPString(defIn.toByteArray());
                case DERTags.BOOLEAN:
                    return new DERBoolean(defIn.toByteArray());
                case DERTags.ENUMERATED:
                    return new DEREnumerated(defIn.toByteArray());
                case DERTags.GENERALIZED_TIME:
                    return new DERGeneralizedTime(defIn.toByteArray());
                case DERTags.GENERAL_STRING:
                    return new DERGeneralString(defIn.toByteArray());
                case DERTags.IA5_STRING:
                    return new DERIA5String(defIn.toByteArray());
                case DERTags.INTEGER:
                    return new DERInteger(defIn.toByteArray());
                case DERTags.NULL:
                    defIn.toByteArray(); // make sure we read to end of object bytes.
                    return DERNull.INSTANCE;
                case DERTags.NUMERIC_STRING:
                  return new DERNumericString(defIn.toByteArray());
                case DERTags.OBJECT_IDENTIFIER:
                    return new DERObjectIdentifier(defIn.toByteArray());
                case DERTags.OCTET_STRING:
                    // TODO Is the handling of definite length constructed encodings correct?
                    if (isConstructed)
                    {
                        return new BEROctetStringParser(new ASN1StreamParser(defIn));
                    }
                    else
                    {
                        return new DEROctetStringParser(defIn);
                    }
                case DERTags.PRINTABLE_STRING:
                    return new DERPrintableString(defIn.toByteArray());
                case DERTags.SEQUENCE:
                    return new DERSequenceParser(new ASN1StreamParser(defIn));
                case DERTags.SET:
                    return new DERSetParser(new ASN1StreamParser(defIn));
                case DERTags.T61_STRING:
                    return new DERT61String(defIn.toByteArray());
                case DERTags.UNIVERSAL_STRING:
                    return new DERUniversalString(defIn.toByteArray());
                case DERTags.UTC_TIME:
                    return new DERUTCTime(defIn.toByteArray());
                case DERTags.UTF8_STRING:
                    return new DERUTF8String(defIn.toByteArray());
                case DERTags.VISIBLE_STRING:
                    return new DERVisibleString(defIn.toByteArray());
                default:
                    return new DERUnknownTag(tag, defIn.toByteArray());
            }
        }
    }

    private void set00Check(boolean enabled)
    {
        if (_in instanceof IndefiniteLengthInputStream)
        {
            ((IndefiniteLengthInputStream)_in).setEofOn00(enabled);
        }
    }

    ASN1EncodableVector readVector() throws IOException
    {
        ASN1EncodableVector v = new ASN1EncodableVector();

        DEREncodable obj;
        while ((obj = readObject()) != null)
        {
            v.add(obj.getDERObject());
        }

        return v;
    }

    private int readTagNumber(int tag)
        throws IOException
    {
        int tagNo = tag & 0x1f;

        //
        // with tagged object tag number is bottom 5 bits, or stored at the start of the content
        //
        if (tagNo == 0x1f)
        {
            tagNo = 0;

            int b = _in.read();

            while ((b >= 0) && ((b & 0x80) != 0))
            {
                tagNo |= (b & 0x7f);
                tagNo <<= 7;
                b = _in.read();
            }

            if (b < 0)
            {
                throw new EOFException("EOF found inside tag value.");
            }

            tagNo |= (b & 0x7f);
        }

        return tagNo;
    }
}
