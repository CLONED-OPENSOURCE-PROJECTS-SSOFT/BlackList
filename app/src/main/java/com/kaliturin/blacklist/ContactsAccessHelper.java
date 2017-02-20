package com.kaliturin.blacklist;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.Telephony;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.kaliturin.blacklist.DatabaseAccessHelper.Contact;
import com.kaliturin.blacklist.DatabaseAccessHelper.ContactNumber;
import com.kaliturin.blacklist.DatabaseAccessHelper.ContactSource;

/**
 * Contacts list access helper
 */
class ContactsAccessHelper {
    private static final String TAG = ContactsAccessHelper.class.getName();
    private static ContactsAccessHelper sInstance = null;
    private ContentResolver contentResolver = null;

    private ContactsAccessHelper(Context context) {
        contentResolver = context.getContentResolver();
    }

    public static synchronized ContactsAccessHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ContactsAccessHelper(context);
        }
        return sInstance;
    }

    private boolean validate(Cursor cursor) {
        if(cursor == null || cursor.isClosed()) return false;
        if(cursor.getCount() == 0) {
            cursor.close();
            return false;
        }
        return true;
    }

    // Types of the contact sources
    enum ContactSourceType {
        FROM_CONTACTS,
        FROM_CALLS_LOG,
        FROM_SMS_INBOX
    }

    // Returns contacts from specified source
    @Nullable
    Cursor getContacts(Context context, ContactSourceType sourceType, @Nullable String filter) {
        switch (sourceType) {
            case FROM_CONTACTS:
                if(Permissions.isGranted(context, Permissions.READ_CONTACTS)) {
                    return getContacts(filter);
                }
                break;
            case FROM_CALLS_LOG:
                if(Permissions.isGranted(context, Permissions.READ_CALL_LOG)) {
                    return getContactsFromCallsLog(filter);
                }
                break;
            case FROM_SMS_INBOX:
                if(Permissions.isGranted(context, Permissions.READ_SMS)) {
                    return getContactsFromSMSInbox(filter);
                }
                break;
        }

        return null;
    }

    // Selects contacts from contacts list
    @Nullable
    private ContactCursorWrapper getContacts(@Nullable String filter) {
        filter = (filter == null ? "%%" : "%" + filter + "%");
        Cursor cursor = contentResolver.query(
                Contacts.CONTENT_URI,
                new String[] {Contacts._ID, Contacts.DISPLAY_NAME},
                Contacts.IN_VISIBLE_GROUP + " != 0 AND " +
                Contacts.HAS_PHONE_NUMBER + " != 0 AND " +
                Contacts.DISPLAY_NAME + " IS NOT NULL AND " +
                Contacts.DISPLAY_NAME + " LIKE ? ",
                new String[]{filter},
                Contacts.DISPLAY_NAME + " ASC");

        return (validate(cursor) ? new ContactCursorWrapper(cursor) : null);
    }

    // Selects contact from contacts list by id
    @Nullable
    private ContactCursorWrapper getContactCursor(long contactId) {
        Cursor cursor = contentResolver.query(
                Contacts.CONTENT_URI,
                new String[] {Contacts._ID, Contacts.DISPLAY_NAME},
                Contacts.DISPLAY_NAME + " IS NOT NULL AND " +
                Contacts.IN_VISIBLE_GROUP + " != 0 AND " +
                Contacts.HAS_PHONE_NUMBER + " != 0 AND " +
                Contacts._ID + " = " + contactId,
                null,
                null);

        return (validate(cursor) ? new ContactCursorWrapper(cursor) : null);
    }

    private @Nullable Contact getContact(long contactId) {
        Contact contact = null;
        ContactCursorWrapper cursor = getContactCursor(contactId);
        if(cursor != null) {
            contact = cursor.getContact(false);
            cursor.close();
        }
        return contact;
    }

    // Selects contact from contacts list by phone number
    @Nullable
    private ContactCursorWrapper getContactCursor(String number) {
        Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));
        Cursor cursor = contentResolver.query(lookupUri,
                new String[]{Contacts._ID, Contacts.DISPLAY_NAME},
                null,
                null,
                null);

        return (validate(cursor) ? new ContactCursorWrapper(cursor) : null);
    }

    @Nullable
    Contact getContact(String number) {
        Contact contact = null;
        ContactCursorWrapper cursor = getContactCursor(number);
        if(cursor != null) {
            contact = cursor.getContact(false);
            cursor.close();
        }
        return contact;
    }

    // Contact's cursor wrapper
    private class ContactCursorWrapper extends CursorWrapper implements ContactSource {
        private final int ID;
        private final int NAME;

        private ContactCursorWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            ID = getColumnIndex(Contacts._ID);
            NAME = getColumnIndex(Contacts.DISPLAY_NAME);
        }

        @Override
        public Contact getContact() {
            return getContact(true);
        }

        Contact getContact(boolean withNumbers) {
            long id = getLong(ID);
            String name = getString(NAME);
            List<ContactNumber> numbers = new LinkedList<>();
            if(withNumbers) {
                ContactNumberCursorWrapper cursor = getContactNumbers(id);
                if(cursor != null) {
                    do {
                        // normalize the phone number (remove spaces and brackets)
                        String number = normalizeContactNumber(cursor.getNumber());
                        // create and add contact number instance
                        ContactNumber contactNumber = new ContactNumber(cursor.getPosition(), number, id);
                        numbers.add(contactNumber);
                    } while (cursor.moveToNext());
                    cursor.close();
                }
            }

            return new Contact(id, name, 0, numbers);
        }
    }

    static String normalizeContactNumber(String number) {
        return number.replaceAll("[-() ]","");
    }

    // Contact's number cursor wrapper
    private static class ContactNumberCursorWrapper extends CursorWrapper {
        private final int NUMBER;

        private ContactNumberCursorWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            NUMBER = cursor.getColumnIndex(Phone.NUMBER);
        }

        String getNumber() {
            return getString(NUMBER);
        }
    }

    // Selects all numbers of specified contact
    @Nullable
    private ContactNumberCursorWrapper getContactNumbers(long contactId) {
        Cursor cursor = contentResolver.query(
                Phone.CONTENT_URI,
                new String[]{Phone.NUMBER},
                Phone.NUMBER + " IS NOT NULL AND " +
                Phone.CONTACT_ID + " = " + contactId,
                null,
                null);

        return (validate(cursor) ? new ContactNumberCursorWrapper(cursor) : null);
    }

//-------------------------------------------------------------------------------------

    // Returns true if passed number contains in SMS inbox
    boolean containsNumberInSMSInbox(@NonNull String number) {
        final String ID = "_id";
        final String ADDRESS = "address";
        final String PERSON = "person";

        Cursor cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                new String[]{"DISTINCT " + ID, ADDRESS, PERSON},
                ADDRESS + " = ? ) GROUP BY (" + ADDRESS,
                new String[]{number},
                "date DESC");

        if(validate(cursor)) {
            cursor.close();
            return true;
        }

        return false;
    }

    // Selects contacts from SMS inbox filtering by contact name or number
    @Nullable
    private ContactFromSMSCursorWrapper getContactsFromSMSInbox(@Nullable String filter) {
        filter = (filter == null ? "" : filter.toLowerCase());
        final String ID = "_id";
        final String ADDRESS = "address"; // number
        final String PERSON = "person"; // contact id

        // filter by address (number) if person (contact id) is null
        Cursor cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                new String[]{"DISTINCT " + ID, ADDRESS, PERSON},
                ADDRESS + " IS NOT NULL AND (" +
                PERSON + " IS NOT NULL OR " +
                ADDRESS + " LIKE ? )" +
                ") GROUP BY (" + ADDRESS,
                new String[]{"%" + filter + "%"},
                "date DESC");

        // now we need to filter contacts by names and fill matrix cursor
        if(cursor != null &&
                cursor.moveToFirst()) {
            MatrixCursor matrixCursor = new MatrixCursor(new String[]{ID, ADDRESS, PERSON});
            final int _ID = cursor.getColumnIndex(ID);
            final int _ADDRESS = cursor.getColumnIndex(ADDRESS);
            final int _PERSON = cursor.getColumnIndex(PERSON);
            do {
                String id = cursor.getString(_ID);
                String address = cursor.getString(_ADDRESS);
                String person = address;
                if(cursor.isNull(_PERSON)) {
                    matrixCursor.addRow(new String[] {id, address, person});
                } else {
                    // get person name from contacts
                    long contactId = cursor.getLong(_PERSON);
                    Contact contact = getContact(contactId);
                    if(contact != null) {
                        person = contact.name;
                    }
                    // filter contact
                    if(person.toLowerCase().contains(filter)) {
                        matrixCursor.addRow(new String[] {id, address, person});
                    }
                }
            } while (cursor.moveToNext());
            cursor.close();
            cursor = matrixCursor;
        }

        return (validate(cursor) ? new ContactFromSMSCursorWrapper(cursor) : null);
    }

    // Contact from SMS cursor wrapper
    private class ContactFromSMSCursorWrapper extends CursorWrapper implements ContactSource {
        private final int ID;
        private final int ADDRESS;
        private final int PERSON;

        private ContactFromSMSCursorWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            ID = getColumnIndex("_id");
            ADDRESS = getColumnIndex("address");
            PERSON = getColumnIndex("person");
        }

        @Override
        public Contact getContact() {
            long id = getLong(ID);
            String name = getString(PERSON);
            String number = getString(ADDRESS);
            List<ContactNumber> numbers = new LinkedList<>();
            numbers.add(new ContactNumber(0, number, id));

            return new Contact(id, name, 0, numbers);
        }
    }

//-------------------------------------------------------------------------------------

    // Selects contacts from calls log
    @Nullable
    private ContactFromCallsCursorWrapper getContactsFromCallsLog(@Nullable String filter) {
        filter = (filter == null ? "%%" : "%" + filter + "%");
        Cursor cursor = null;
        // This try/catch is required by IDE because we use Calls.CONTENT_URI
        try {
            // filter by name or by number
            cursor = contentResolver.query(
                    Calls.CONTENT_URI,
                    new String[] {Calls._ID, Calls.NUMBER, Calls.CACHED_NAME},
                    Calls.NUMBER + " IS NOT NULL AND (" +
                    Calls.CACHED_NAME + " IS NULL AND " +
                    Calls.NUMBER + " LIKE ? OR " +
                    Calls.CACHED_NAME + " LIKE ? )",
                    new String[]{filter, filter},
                    Calls.DATE + " DESC");
        } catch (SecurityException e) {
            Log.w(TAG, e);
        }

        if(cursor != null &&
                cursor.moveToFirst()) {
            // Because we cannot query distinct calls - we have queried all.
            // And now we must get rid of repeated calls with help of tree set and matrix cursor.
            MatrixCursor matrixCursor = new MatrixCursor(
                    new String[]{Calls._ID, Calls.NUMBER, Calls.CACHED_NAME});
            final int ID = cursor.getColumnIndex(Calls._ID);
            final int NUMBER = cursor.getColumnIndex(Calls.NUMBER);
            final int NAME = cursor.getColumnIndex(Calls.CACHED_NAME);
            Set<String> set = new TreeSet<>();
            do {
                String number = cursor.getString(NUMBER);
                String name = cursor.getString(NAME);
                String key = number + (name == null ? "" : name);
                if(set.add(key)) {
                    String id = cursor.getString(ID);
                    matrixCursor.addRow(new String[] {id, number, name});
                }
            } while (cursor.moveToNext());
            cursor.close();
            cursor = matrixCursor;
        }

        return (validate(cursor) ? new ContactFromCallsCursorWrapper(cursor) : null);
    }

    // Contact from calls cursor wrapper
    private class ContactFromCallsCursorWrapper extends  CursorWrapper implements ContactSource {
        private final int ID;
        private final int NUMBER;
        private final int NAME;

        private ContactFromCallsCursorWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            ID = cursor.getColumnIndex(Calls._ID);
            NUMBER = cursor.getColumnIndex(Calls.NUMBER);
            NAME = cursor.getColumnIndex(Calls.CACHED_NAME);
        }

        @Override
        public Contact getContact() {
            long id = getLong(ID);
            String number = getString(NUMBER);
            String name = getString(NAME);
            List<ContactNumber> numbers = new LinkedList<>();
            numbers.add(new ContactNumber(0, number, id));
            if(name == null) {
                name = number;
            }

            return new Contact(id, name, 0, numbers);
        }
    }

//-------------------------------------------------------------------------------------

    // SMS conversation
    class SMSConversation {
        final int threadId;
        final long date;
        final String snippet;
        final String address;
        final int unread;

        SMSConversation(int threadId, long date, String address, String snippet, int unread) {
            this.threadId = threadId;
            this.date = date;
            this.address = address;
            this.snippet = snippet;
            this.unread = unread;
        }
    }


    // SMS conversation cursor wrapper
    class SMSConversationWrapper extends CursorWrapper {
        private final int THREAD_ID;
        private final int DATE;
        private final int ADDRESS;
        private final int SNIPPET;
        private final int UNREAD;

        private SMSConversationWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            THREAD_ID = cursor.getColumnIndex("thread_id");
            DATE = cursor.getColumnIndex("date");
            ADDRESS = cursor.getColumnIndex("address");
            SNIPPET = cursor.getColumnIndex("snippet");
            UNREAD = cursor.getColumnIndex("unread");
        }

        SMSConversation getConversation() {
            int threadId = getInt(THREAD_ID);
            long date = getLong(DATE);
            String address = getString(ADDRESS);
            String snippet = getString(SNIPPET);
            int unread = getInt(UNREAD);

            return new SMSConversation(threadId, date, address, snippet, unread);
        }
    }

    // Returns SMS conversation cursor wrapper
    @Nullable
    SMSConversationWrapper getSMSConversations(Context context) {
        if(!Permissions.isGranted(context, Permissions.READ_SMS)) {
            return null;
        }

        // select available conversation's data
        Cursor cursor = contentResolver.query(
                Uri.parse("content://sms/conversations"),
                null,
                null,
                null,
                "date DESC");

        // create new matrix cursor that combines selected conversation's data with
        // id, address, and date of conversation
        if(cursor != null &&
                cursor.moveToFirst()) {

            // resulting matrix cursor
            MatrixCursor matrixCursor = new MatrixCursor(
                    new String[]{"_id", "thread_id", "snippet", "address", "date", "unread"});

            final int THREAD_ID = cursor.getColumnIndex("thread_id");
            final int SNIPPET = cursor.getColumnIndex("snippet");
            do {
                int threadId = cursor.getInt(THREAD_ID);
                String snippet = cursor.getString(SNIPPET);
                // get the count of unread SMS in the thread
                int unread = getSMSUnreadCountByThreadId(context, threadId);
                // find date and address by the last SMS from the thread
                SMSRecordCursorWrapper smsRecordCursor =
                        getSMSRecordsByThreadId(context, threadId, true, 1);
                if(smsRecordCursor != null) {
                    SMSRecord smsRecord = smsRecordCursor.getSMSRecord();
                    smsRecordCursor.close();

                    // add row to the resulting cursor
                    String sThreadId = String.valueOf(threadId);
                    matrixCursor.addRow(new String[]{
                            sThreadId, sThreadId, snippet,
                            smsRecord.address,
                            String.valueOf(smsRecord.date),
                            String.valueOf(unread)});
                }
            } while (cursor.moveToNext());
            cursor.close();
            // swap cursor
            cursor = matrixCursor;
        }

        return (validate(cursor) ? new SMSConversationWrapper(cursor) : null);
    }

    // Selects SMS records by thread id
    @Nullable
    SMSRecordCursorWrapper getSMSRecordsByThreadId(Context context, int threadId, boolean desc, int limit) {
        if(!Permissions.isGranted(context, Permissions.READ_SMS) ||
                !Permissions.isGranted(context, Permissions.READ_CONTACTS)) {
            return null;
        }

        String orderClause = (desc ? " date DESC " : " date ASC ");
        String limitClause = (limit > 0 ? " LIMIT " + limit : "");
        Cursor cursor = contentResolver.query(
                Uri.parse("content://sms/"),
                null,
                " thread_id = " + threadId,
                null,
                orderClause + limitClause);

        return (validate(cursor) ? new SMSRecordCursorWrapper(cursor) : null);
    }

    // Returns count of unread SMS by thread id
    private int getSMSUnreadCountByThreadId(Context context, int threadId) {
        if(!Permissions.isGranted(context, Permissions.READ_SMS)) {
            return 0;
        }

        Cursor cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                null,
                " thread_id = " + threadId + " AND " +
                " read = 0 ",
                null,
                null);

        int count = 0;
        if(validate(cursor)) {
            count = cursor.getCount();
            cursor.close();
        }

        return count;
    }

    // Sets SMS are read by thread id
    boolean setSMSReadByThreadId(Context context, int threadId) {
        if(!Permissions.isGranted(context, Permissions.WRITE_SMS)) {
            return false;
        }

        ContentValues values = new ContentValues();
        values.put("read", 1);
        contentResolver.update(
                Uri.parse("content://sms/inbox"),
                values,
                " thread_id = " + threadId + " AND " +
                " read = 0 ",
                null);

        return true;
    }

    // Deletes SMS by thread id
    boolean deleteSMSByThreadId(Context context, int threadId) {
        if(!Permissions.isGranted(context, Permissions.WRITE_SMS)) {
            return false;
        }

        int count = contentResolver.delete(
                Uri.parse("content://sms"),
                " thread_id = " + threadId,
                null);

        return (count > 0);
    }

    // Sets all SMS are seen
    boolean setSMSSeen(Context context) {
        if(!Permissions.isGranted(context, Permissions.WRITE_SMS)) {
            return false;
        }

        ContentValues values = new ContentValues();
        values.put("seen", 1);
        contentResolver.update(
                Uri.parse("content://sms/inbox"),
                values,
                " seen = 0 ",
                null);

        return true;
    }

    // SMS record
    class SMSRecord {
        static final int TYPE_INBOX = 1;

        final long id;
        final int type;
        final long date;
        final String address;
        final String body;

        SMSRecord(long id, int type, long date, String address, String body) {
            this.id = id;
            this.type = type;
            this.date = date;
            this.address = address;
            this.body = body;
        }
    }

    // SMS record cursor wrapper
    class SMSRecordCursorWrapper extends CursorWrapper {
        private final int ID;
        private final int TYPE;
        private final int DATE;
        private final int ADDRESS;
        private final int PERSON;
        private final int BODY;

        private SMSRecordCursorWrapper(Cursor cursor) {
            super(cursor);
            cursor.moveToFirst();
            ID = cursor.getColumnIndex("_id");
            TYPE = cursor.getColumnIndex("type");
            DATE = cursor.getColumnIndex("date");
            ADDRESS = cursor.getColumnIndex("address");
            PERSON = cursor.getColumnIndex("person");
            BODY = cursor.getColumnIndex("body");
        }

        SMSRecord getSMSRecord() {
            long id = getLong(ID);
            int type = getInt(TYPE);
            long date = getLong(DATE);
            String address = getString(ADDRESS);
            Contact contact;
            if(!isNull(PERSON)) {
                // if person is defined - get contact name
                long contactId = getLong(PERSON);
                contact = getContact(contactId);
            } else {
                contact = getContact(address);
            }
            if(contact != null) {
                address = contact.name;
            }
            String body = getString(BODY);

            return new SMSRecord(id, type, date, address, body);
        }
    }

    // Writes SMS messages to the inbox
    // Needed only for API19 and newer - where only default SMS app can write to the inbox
    @TargetApi(19)
    boolean writeSMSToInbox(Context context, SmsMessage[] messages) {
        // check write permission
        if(!Permissions.isGranted(context, Permissions.WRITE_SMS)) return false;

        for (SmsMessage message : messages) {
            // get contact by SMS address
            Contact contact = getContact(message.getOriginatingAddress());

            // create writing values
            ContentValues values = new ContentValues();
            values.put(Telephony.Sms.ADDRESS, message.getDisplayOriginatingAddress());
            values.put(Telephony.Sms.BODY, message.getMessageBody());
            values.put(Telephony.Sms.PERSON, (contact == null ? null : contact.id));
            values.put(Telephony.Sms.DATE_SENT, message.getTimestampMillis());
            values.put(Telephony.Sms.PROTOCOL, message.getProtocolIdentifier());
            values.put(Telephony.Sms.REPLY_PATH_PRESENT, message.isReplyPathPresent());
            values.put(Telephony.Sms.SERVICE_CENTER, message.getServiceCenterAddress());
            values.put(Telephony.Sms.READ, "0");
            values.put(Telephony.Sms.SEEN, "0");

            // write SMS to Inbox
            contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        }

        return true;
    }

    private static void debug(Cursor cursor) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            String s = cursor.getString(i);
            String n = cursor.getColumnName(i);
            sb.append("[").append(n).append("]=").append(s);
        }
        Log.d(TAG, sb.toString());
    }

    /*
    SMS table row example:
    [_id]=6
    [thread_id]=5
    [address]=123
    [person]=null
    [date]=1485692853433
    [date_sent]=1485692853000
    [protocol]=0
    [read]=0
    [status]=-1
    [type]=1
    [reply_path_present]=0
    [subject]=null
    [body]=Don't forget the marshmallows!
    [service_center]=null
    [locked]=0
    [error_code]=0
    [seen]=0
     */
}
