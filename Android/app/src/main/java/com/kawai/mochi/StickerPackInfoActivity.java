package com.kawai.mochi;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.kawai.mochi.R;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

public class StickerPackInfoActivity extends BaseActivity {

    private static final String TAG = "StickerPackInfoActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker_pack_info);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.pack_diagnostics_title);
        }

        // ── Read all extras ──
        final String trayIconUriString = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TRAY_ICON);
        final String packName          = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_NAME);
        final String packId            = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_ID);
        final String publisher         = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PUBLISHER);
        final String website           = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_WEBSITE);
        final String email             = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_EMAIL);
        final String privacyPolicy     = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_PRIVACY_POLICY);
        final String licenseAgreement  = getIntent().getStringExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_LICENSE_AGREEMENT);
        final int stickerCount         = getIntent().getIntExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_STICKER_COUNT, 0);
        final long totalSize           = getIntent().getLongExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_TOTAL_SIZE, 0);
        final StickerPack stickerPack  = getIntent().getParcelableExtra(StickerPackDetailsActivity.EXTRA_STICKER_PACK_DATA);

        // ── UI Setup ──
        View summaryView = findViewById(R.id.summary_container);
        View detailsView = findViewById(R.id.details_container);
        TabLayout tabLayout = findViewById(R.id.info_tabs);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    summaryView.setVisibility(View.VISIBLE);
                    detailsView.setVisibility(View.GONE);
                } else {
                    summaryView.setVisibility(View.GONE);
                    detailsView.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // ── Tray image ──
        ImageView trayImageView = findViewById(R.id.info_tray_image);
        if (!TextUtils.isEmpty(trayIconUriString)) {
            try {
                Uri trayUri = Uri.parse(trayIconUriString);
                InputStream inputStream = getContentResolver().openInputStream(trayUri);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(inputStream);
                trayImageView.setImageBitmap(bmp);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Could not find tray icon: " + trayIconUriString);
            }
        }

        // ── Pack Summary Fields ──
        ((TextView) findViewById(R.id.info_pack_name)).setText(
                TextUtils.isEmpty(packName) ? getString(R.string.default_sticker_pack_name) : packName);

        TextView publisherView = findViewById(R.id.info_publisher);
        if (!TextUtils.isEmpty(publisher)) {
            publisherView.setText(publisher);
            publisherView.setVisibility(View.VISIBLE);
        }

        ((TextView) findViewById(R.id.info_total_size)).setText(Formatter.formatShortFileSize(this, totalSize));
        ((TextView) findViewById(R.id.info_pack_id)).setText(TextUtils.isEmpty(packId) ? "—" : packId);

        // Diagnostics
        boolean isAnimated = detectPackAnimated(packId, stickerPack);
        ((TextView) findViewById(R.id.info_animated)).setText(isAnimated ? getString(R.string.animated_pack_flag) : getString(R.string.static_pack));

        int webpCount = 0;
        if (stickerPack != null && stickerPack.getStickers() != null) {
            for (Sticker s : stickerPack.getStickers()) {
                if (s.imageFileName.toLowerCase().endsWith(".webp")) webpCount++;
            }
            ((TextView) findViewById(R.id.info_composition)).setText(
                    getString(R.string.sticker_composition_format, stickerPack.getStickers().size(), webpCount));
        }

        // ── Sticker details RecyclerView ──
        if (stickerPack != null) {
            List<Sticker> stickers = stickerPack.getStickers();
            if (stickers != null && !stickers.isEmpty()) {
                TextView header = findViewById(R.id.info_stickers_header);
                header.setText(getString(R.string.sticker_audit_header, stickers.size()));

                RecyclerView rv = findViewById(R.id.info_sticker_list);
                LinearLayoutManager lm = new LinearLayoutManager(this);
                rv.setLayoutManager(lm);
                rv.addItemDecoration(new DividerItemDecoration(this, lm.getOrientation()));
                rv.setAdapter(new StickerInfoAdapter(this, stickers, packId));
            }
        }

        // ── Links ──
        boolean hasLinks = !TextUtils.isEmpty(website) || !TextUtils.isEmpty(email)
                || !TextUtils.isEmpty(privacyPolicy) || !TextUtils.isEmpty(licenseAgreement);
        if (hasLinks) {
            findViewById(R.id.publisher_links_section).setVisibility(View.VISIBLE);
        }
        setupLink(website,          R.id.view_webpage,       () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(website))));
        setupLink(email,            R.id.send_email,         () -> {
            Intent em = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null));
            em.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
            startActivity(Intent.createChooser(em, getString(R.string.info_send_email_to_prompt)));
        });
        setupLink(privacyPolicy,    R.id.privacy_policy,     () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicy))));
        setupLink(licenseAgreement, R.id.license_agreement,  () ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(licenseAgreement))));
    }

    private boolean detectPackAnimated(String packId, StickerPack pack) {
        if (pack == null || TextUtils.isEmpty(packId)) return false;
        if (pack.animatedStickerPack) return true;
        List<Sticker> stickers = pack.getStickers();
        if (stickers == null || stickers.isEmpty()) return false;
        return WastickerParser.isAnimatedWebPPublic(this, packId, stickers.get(0).imageFileName);
    }

    private void setupLink(String value, int viewId, Runnable onClick) {
        TextView tv = findViewById(viewId);
        if (TextUtils.isEmpty(value)) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setVisibility(View.VISIBLE);
            tv.setOnClickListener(v -> onClick.run());
        }
    }
}
