using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace Sadl
{
    /// <summary>
    /// Writes a decoded licence in the canonical JSON form of spec/output-format.md, the form the conformance
    /// vectors expect. The output holds personal data: show it or send it, but do not log it.
    /// </summary>
    /// <remarks>
    /// On .NET 8 and later the JSON is written with System.Text.Json's <c>Utf8JsonWriter</c>. On .NET Standard 2.1,
    /// to keep the package free of dependencies, a small built-in writer is used instead; both produce the same JSON
    /// value (non-ASCII characters are written as <c>\uXXXX</c> escapes by both).
    /// </remarks>
    public static class CanonicalJson
    {
        /// <summary>The canonical JSON of a decoded barcode.</summary>
        public static string Of(DecodedBarcode barcode) => barcode switch
        {
            DecodedBarcode.Card c => Of(c.Licence),
            DecodedBarcode.Temporary t => Of(t.Licence),
            _ => throw new ArgumentOutOfRangeException(nameof(barcode)),
        };

        /// <summary>The canonical JSON of a card.</summary>
        public static string Of(CardLicence card) => Write(w => WriteCard(w, card));

        /// <summary>The canonical JSON of a temporary licence.</summary>
        public static string Of(TemporaryLicence licence) => Write(w => WriteTemporary(w, licence));

        /// <summary>The canonical error form, for example <c>{"error": "block_check_failed"}</c>.</summary>
        public static string Of(LicenceBarcodeException error) => Write(w =>
        {
            w.StartObject();
            w.Property("error", error.ReasonCode);
            w.EndObject();
        });

        internal static string Write(Action<IJsonSink> body, bool forceBuiltIn = false)
        {
#if NET8_0_OR_GREATER
            if (!forceBuiltIn)
            {
                using var sink = new SystemTextJsonSink();
                body(sink);
                return sink.ToString();
            }
#endif
            var simple = new BuiltInJsonSink();
            body(simple);
            return simple.ToString();
        }

        internal static void WriteCard(IJsonSink w, CardLicence c)
        {
            w.StartObject();
            w.Property("kind", "card");
            w.Property("version", c.Version);
            w.Property("licence_number", c.LicenceNumber);
            w.Property("licence_country", c.LicenceCountry);
            w.Property("surname", c.Surname);
            w.Property("initials", c.Initials);
            w.Property("id_number", c.IdNumber);
            w.Property("id_type", c.IdType);
            w.Property("id_country", c.IdCountry);
            w.Property("birth_date", Iso(c.BirthDate));
            w.Property("gender", c.GenderCode);
            w.Property("valid_from", Iso(c.ValidFrom));
            w.Property("valid_to", Iso(c.ValidTo));
            w.Property("issue_number", c.IssueNumber);
            WriteCodes(w, c.Codes);
            w.Property("driver_restrictions", c.DriverRestrictions);
            WriteStrings(w, "prdp_categories", c.PrdpCategories);
            w.Property("prdp_expiry", Iso(c.PrdpExpiry));
            w.Property("photo_length", c.Photo?.Length ?? 0);
            w.EndObject();
        }

        internal static void WriteTemporary(IJsonSink w, TemporaryLicence t)
        {
            w.StartObject();
            w.Property("kind", "temporary_licence");
            w.Property("tag", t.Tag);
            w.Property("field2", t.Field2);
            w.Property("serial", t.Serial);
            w.Property("field4", t.Field4);
            w.Property("licence_number", t.LicenceNumber);
            w.Property("id_type", t.IdType);
            w.Property("id_number", t.IdNumber);
            w.Property("name", t.Name);
            WriteCodes(w, t.Codes);
            WriteStrings(w, "prdp_categories", t.PrdpCategories);
            w.Property("prdp_expiry", Iso(t.PrdpExpiry));
            w.Property("issue_date", Iso(t.IssueDate));
            w.Property("valid_to", Iso(t.ValidTo));
            w.EndObject();
        }

        private static void WriteCodes(IJsonSink w, IReadOnlyList<VehicleCode> codes)
        {
            w.Name("vehicle_codes");
            w.StartArray();
            foreach (var code in codes)
            {
                w.StartObject();
                w.Property("code", code.Code);
                w.Property("vehicle_restriction", code.VehicleRestriction);
                w.Property("first_issue", Iso(code.FirstIssue));
                w.EndObject();
            }
            w.EndArray();
        }

        private static void WriteStrings(IJsonSink w, string name, IReadOnlyList<string> values)
        {
            w.Name(name);
            w.StartArray();
            foreach (var v in values) w.String(v);
            w.EndArray();
        }

        /// <summary>yyyy-MM-dd with ASCII digits whatever the current culture.</summary>
        private static string? Iso(DateTime? date) =>
            date?.ToString("yyyy'-'MM'-'dd", CultureInfo.InvariantCulture);
    }

    /// <summary>The few JSON operations the canonical form needs.</summary>
    internal interface IJsonSink
    {
        void StartObject();
        void EndObject();
        void StartArray();
        void EndArray();
        void Name(string name);
        void String(string? value);
        void Number(int value);
    }

    internal static class JsonSinkExtensions
    {
        public static void Property(this IJsonSink w, string name, string? value)
        {
            w.Name(name);
            w.String(value);
        }

        public static void Property(this IJsonSink w, string name, int value)
        {
            w.Name(name);
            w.Number(value);
        }
    }

    /// <summary>A small JSON writer for targets without System.Text.Json: <c>{"a": 1, "b": [..]}</c>, ASCII only.</summary>
    internal sealed class BuiltInJsonSink : IJsonSink
    {
        private readonly StringBuilder text = new StringBuilder();
        private readonly Stack<bool> first = new Stack<bool>();
        private bool afterName;

        private void BeforeValue()
        {
            if (afterName)
            {
                afterName = false;
                return;
            }
            if (first.Count > 0)
            {
                if (!first.Pop()) text.Append(", ");
                first.Push(false);
            }
        }

        public void StartObject()
        {
            BeforeValue();
            text.Append('{');
            first.Push(true);
        }

        public void EndObject()
        {
            first.Pop();
            text.Append('}');
        }

        public void StartArray()
        {
            BeforeValue();
            text.Append('[');
            first.Push(true);
        }

        public void EndArray()
        {
            first.Pop();
            text.Append(']');
        }

        public void Name(string name)
        {
            BeforeValue();
            Quote(name);
            text.Append(": ");
            afterName = true;
        }

        public void String(string? value)
        {
            BeforeValue();
            if (value == null) text.Append("null");
            else Quote(value);
        }

        public void Number(int value)
        {
            BeforeValue();
            text.Append(value.ToString(CultureInfo.InvariantCulture));
        }

        private void Quote(string s)
        {
            text.Append('"');
            foreach (char ch in s)
            {
                if (ch == '"') text.Append("\\\"");
                else if (ch == '\\') text.Append("\\\\");
                else if (ch < ' ' || ch > '~') text.Append("\\u").Append(((int)ch).ToString("x4", CultureInfo.InvariantCulture));
                else text.Append(ch);
            }
            text.Append('"');
        }

        public override string ToString() => text.ToString();
    }

#if NET8_0_OR_GREATER
    /// <summary>The canonical form through System.Text.Json's Utf8JsonWriter (default, ASCII-safe escaping).</summary>
    internal sealed class SystemTextJsonSink : IJsonSink, IDisposable
    {
        private readonly System.IO.MemoryStream buffer = new System.IO.MemoryStream();
        private readonly System.Text.Json.Utf8JsonWriter writer;

        public SystemTextJsonSink() => writer = new System.Text.Json.Utf8JsonWriter(buffer);

        public void StartObject() => writer.WriteStartObject();

        public void EndObject() => writer.WriteEndObject();

        public void StartArray() => writer.WriteStartArray();

        public void EndArray() => writer.WriteEndArray();

        public void Name(string name) => writer.WritePropertyName(name);

        public void String(string? value)
        {
            if (value == null) writer.WriteNullValue();
            else writer.WriteStringValue(value);
        }

        public void Number(int value) => writer.WriteNumberValue(value);

        public override string ToString()
        {
            writer.Flush();
            return Encoding.UTF8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length);
        }

        public void Dispose()
        {
            writer.Dispose();
            buffer.Dispose();
        }
    }
#endif
}
