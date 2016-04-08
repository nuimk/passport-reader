/*
 * Copyright 2016 Anton Tananaev (anton.tananaev@gmail.com)
 *
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
package com.tananaev.passportreader;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;

import net.sf.scuba.smartcards.CardFileInputStream;
import net.sf.scuba.smartcards.CardService;

import org.jmrtd.BACKey;
import org.jmrtd.BACKeySpec;
import org.jmrtd.PassportService;
import org.jmrtd.lds.COMFile;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.DG2File;
import org.jmrtd.lds.LDS;
import org.jmrtd.lds.MRZInfo;
import org.jmrtd.lds.SODFile;

import java.security.Security;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Locale;
import java.util.TreeSet;

public class MainActivity extends AppCompatActivity {

    static {
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    private final static String KEY_PASSPORT_NUMBER = "passportNumber";
    private final static String KEY_EXPIRATION_DATE = "expirationDate";
    private final static String KEY_BIRTH_DATE = "birthDate";

    public static class DatePickerFragment extends DialogFragment implements DatePickerDialog.OnDateSetListener {

        private final static String KEY_VIEW_ID = "viewId";
        private final static String KEY_PREFERENCE = "preferenceKey";

        public static DatePickerFragment createInstance(int viewId, String preferenceKey) {
            DatePickerFragment fragment = new DatePickerFragment();
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_VIEW_ID, viewId);
            bundle.putString(KEY_PREFERENCE, preferenceKey);
            fragment.setArguments(bundle);
            return fragment;
        }

        EditText editText;

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            editText = (EditText) getActivity().findViewById(getArguments().getInt(KEY_VIEW_ID));

            Calendar c = Calendar.getInstance();
            if (!editText.getText().toString().isEmpty()) {
                try {
                    c.setTimeInMillis(new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .parse(editText.getText().toString()).getTime());
                } catch (ParseException e) {
                    Log.w(MainActivity.class.getSimpleName(), e);
                }
            }

            return new DatePickerDialog(getActivity(), this,
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        }

        @Override
        public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
            String value = String.format("%d-%02d-%02d", year, monthOfYear + 1, dayOfMonth);
            PreferenceManager.getDefaultSharedPreferences(getActivity())
                    .edit().putString(getArguments().getString(KEY_PREFERENCE), value).apply();
            editText.setText(value);
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            editText.clearFocus();
        }

    }

    EditText passportNumberView;
    EditText expirationDateView;
    EditText birthDateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        passportNumberView = (EditText) findViewById(R.id.input_passport_number);
        expirationDateView = (EditText) findViewById(R.id.input_expiration_date);
        birthDateView = (EditText) findViewById(R.id.input_date_of_birth);

        passportNumberView.setText(preferences.getString(KEY_PASSPORT_NUMBER, null));
        expirationDateView.setText(preferences.getString(KEY_EXPIRATION_DATE, null));
        birthDateView.setText(preferences.getString(KEY_BIRTH_DATE, null));

        passportNumberView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                        .edit().putString(KEY_PASSPORT_NUMBER, s.toString()).apply();
            }
        });

        expirationDateView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    getSupportFragmentManager().beginTransaction().add(
                            DatePickerFragment.createInstance(R.id.input_expiration_date, KEY_EXPIRATION_DATE), null).commit();
                }
            }
        });

        birthDateView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    getSupportFragmentManager().beginTransaction().add(
                            DatePickerFragment.createInstance(R.id.input_date_of_birth, KEY_BIRTH_DATE), null).commit();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        Intent intent = new Intent(getApplicationContext(), this.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        String[][] filter = new String[][] { new String[] { "android.nfc.tech.IsoDep" } };
        adapter.enableForegroundDispatch(this, pendingIntent, null, filter);
    }

    private static String convertDate(String input) {
        if (input == null) {
            return null;
        }
        try {
            return new SimpleDateFormat("yyMMdd", Locale.US)
                    .format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(input));
        } catch (ParseException e) {
            Log.w(MainActivity.class.getSimpleName(), e);
            return null;
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getExtras().getParcelable(NfcAdapter.EXTRA_TAG);
            if (Arrays.asList(tag.getTechList()).contains("android.nfc.tech.IsoDep")) {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                String passportNumber = preferences.getString(KEY_PASSPORT_NUMBER, null);
                String expirationDate = convertDate(preferences.getString(KEY_EXPIRATION_DATE, null));
                String birthDate = convertDate(preferences.getString(KEY_BIRTH_DATE, null));
                if (passportNumber != null && !passportNumber.isEmpty()
                        && expirationDate != null && !expirationDate.isEmpty()
                        && birthDate != null && !birthDate.isEmpty()) {
                    BACKeySpec bacKey = new BACKey(passportNumber, birthDate, expirationDate);
                    new ReadTask(IsoDep.get(tag), bacKey).execute();
                } else {
                    Snackbar.make(passportNumberView, R.string.error_input, Snackbar.LENGTH_SHORT).show();
                }
            }
        }
    }

    private class ReadTask extends AsyncTask<Void, Void, Boolean> {

        private IsoDep isoDep;
        private BACKeySpec bacKey;

        public ReadTask(IsoDep isoDep, BACKeySpec bacKey) {
            this.isoDep = isoDep;
            this.bacKey = bacKey;
        }

        private COMFile comFile;
        private SODFile sodFile;
        private DG1File dg1File;
        private DG2File dg2File;

        @Override
        protected Boolean doInBackground(Void... params) {
            try {

                CardService cardService = CardService.getInstance(isoDep);
                cardService.open();

                PassportService service = new PassportService(cardService);
                service.open();

                service.sendSelectApplet(false);

                service.doBAC(bacKey);

                LDS lds = new LDS();

                CardFileInputStream comIn = service.getInputStream(PassportService.EF_COM);
                lds.add(PassportService.EF_COM, comIn, comIn.getLength());
                comFile = lds.getCOMFile();

                CardFileInputStream sodIn = service.getInputStream(PassportService.EF_SOD);
                lds.add(PassportService.EF_SOD, sodIn, sodIn.getLength());
                sodFile = lds.getSODFile();

                CardFileInputStream dg1In = service.getInputStream(PassportService.EF_DG1);
                lds.add(PassportService.EF_DG1, dg1In, dg1In.getLength());
                dg1File = lds.getDG1File();

                CardFileInputStream dg2In = service.getInputStream(PassportService.EF_DG2);
                lds.add(PassportService.EF_DG2, dg2In, dg2In.getLength());
                dg2File = lds.getDG2File();

            } catch (Exception e) {
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {

                MRZInfo mrzInfo = dg1File.getMRZInfo();
                /*documentNumberW.setText(mrzInfo.getDocumentNumber());
                personalNumberW.setText(mrzInfo.getPersonalNumber());
                issuingStateW.setText(mrzInfo.getIssuingState());
                primaryIdentifierW.setText(mrzInfo.getPrimaryIdentifier().replace("<", " ").trim());
                secondaryIdentifiersW.setText(mrzInfo.getSecondaryIdentifier().replace("<", " ").trim());
                genderW.setText(mrzInfo.getGender().toString());
                nationalityW.setText(mrzInfo.getNationality());
                dobW.setText(mrzInfo.getDateOfBirth());
                doeW.setText(mrzInfo.getDateOfExpiry());*/

                // TODO


            } else {
                Snackbar.make(passportNumberView, R.string.error_read, Snackbar.LENGTH_SHORT).show();
            }
        }

    }

}
