package com.superbiz.app.pdf.statement

import com.superbiz.app.domain.statement.StatementLang

/**
 * ترجمات كشف الحساب للغات اللاتينية (FR/TR/ID) — مفتاحها النص الإنجليزي المستخدم في lbl(ar, en)،
 * فيبقى هيكل PDF واحداً (LTR كالإنجليزي) وتتبدل النصوص فقط. غياب المفتاح يرجع للإنجليزية.
 */
object StatementI18n {

    fun isLatinExtra(l: StatementLang) = l == StatementLang.FR || l == StatementLang.TR || l == StatementLang.ID

    private val T: Map<String, Array<String>> = mapOf(
        // en to [fr, tr, id]
        "Company No" to arrayOf("N° société", "Şirket No", "No. perusahaan"),
        "Tax No" to arrayOf("N° fiscal", "Vergi No", "NPWP"),
        "CR No" to arrayOf("N° RCS", "Ticaret Sicil No", "No. izin usaha"),
        "Statement of Account" to arrayOf("Relevé de compte", "Hesap Ekstresi", "Rekening Koran"),
        "Statement No" to arrayOf("N° du relevé", "Ekstre No", "No. rekening koran"),
        "Customer" to arrayOf("Client", "Müşteri", "Pelanggan"),
        "Party No" to arrayOf("N° du tiers", "Cari No", "No. pihak"),
        "Phone" to arrayOf("Téléphone", "Telefon", "Telepon"),
        "Email" to arrayOf("E-mail", "E-posta", "Email"),
        "Address" to arrayOf("Adresse", "Adres", "Alamat"),
        "Account" to arrayOf("Compte", "Hesap", "Akun"),
        "Invoice" to arrayOf("Facture", "Fatura", "Faktur"),
        "Payment" to arrayOf("Paiement", "Ödeme", "Pembayaran"),
        "Debt" to arrayOf("Dette", "Borç", "Utang"),
        "Check" to arrayOf("Chèque", "Çek", "Cek"),
        "Installment" to arrayOf("Échéance", "Taksit", "Cicilan"),
        "Adjust" to arrayOf("Ajustement", "Düzeltme", "Penyesuaian"),
        "Date" to arrayOf("Date", "Tarih", "Tanggal"),
        "Ref" to arrayOf("Réf.", "Ref.", "Ref."),
        "Description" to arrayOf("Libellé", "Açıklama", "Keterangan"),
        "Debit" to arrayOf("Débit", "Borç", "Debit"),
        "Credit" to arrayOf("Crédit", "Alacak", "Kredit"),
        "Balance" to arrayOf("Solde", "Bakiye", "Saldo"),
        "Opening Balance" to arrayOf("Solde d'ouverture", "Açılış Bakiyesi", "Saldo awal"),
        "Totals" to arrayOf("Totaux", "Toplamlar", "Total"),
        "Opening" to arrayOf("Ouverture", "Açılış", "Awal"),
        "Total Debit" to arrayOf("Total débit", "Toplam Borç", "Total debit"),
        "Total Credit" to arrayOf("Total crédit", "Toplam Alacak", "Total kredit"),
        "Payments" to arrayOf("Paiements", "Ödemeler", "Pembayaran"),
        "Invoices" to arrayOf("Factures", "Faturalar", "Faktur"),
        "Discounts" to arrayOf("Remises", "İndirimler", "Diskon"),
        "Final Balance" to arrayOf("Solde final", "Nihai Bakiye", "Saldo akhir"),
        "Amount due on customer" to arrayOf("Montant dû par le client", "Müşteriden alacak", "Jumlah terutang pelanggan"),
        "Credit in customer's favor" to arrayOf("Crédit en faveur du client", "Müşteri lehine alacak", "Kredit untuk pelanggan"),
        "Balanced — no dues" to arrayOf("Soldé — aucune dette", "Dengeli — borç yok", "Seimbang — tidak ada utang"),
        "Notes" to arrayOf("Notes", "Notlar", "Catatan"),
        "Signature" to arrayOf("Signature", "İmza", "Tanda tangan"),
        "No transactions in this period" to arrayOf("Aucune opération sur cette période", "Bu dönemde işlem yok", "Tidak ada transaksi pada periode ini"),
    )

    private fun idx(l: StatementLang) = when (l) { StatementLang.FR -> 0; StatementLang.TR -> 1; else -> 2 }

    /** ترجمة نص إنجليزي؛ يرجع الأصل إن لم يوجد */
    fun tr(en: String, l: StatementLang): String = T[en]?.get(idx(l)) ?: en

    val FR_MONTHS = arrayOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
    val TR_MONTHS = arrayOf("Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara")
    val ID_MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    fun month(m: Int, l: StatementLang) = when (l) { StatementLang.FR -> FR_MONTHS[m]; StatementLang.TR -> TR_MONTHS[m]; else -> ID_MONTHS[m] }

    fun pageOf(p: Int, t: Int, l: StatementLang) = when (l) {
        StatementLang.FR -> "Page $p sur $t"; StatementLang.TR -> "Sayfa $p / $t"; else -> "Halaman $p dari $t"
    }
    fun generated(d: String, l: StatementLang) = when (l) {
        StatementLang.FR -> "Émis le : $d"; StatementLang.TR -> "Düzenlenme: $d"; else -> "Diterbitkan: $d"
    }
    fun period(a: String, b: String, l: StatementLang) = when (l) {
        StatementLang.FR -> "Du $a au $b"; StatementLang.TR -> "$a - $b arası"; else -> "Dari $a hingga $b"
    }
}
