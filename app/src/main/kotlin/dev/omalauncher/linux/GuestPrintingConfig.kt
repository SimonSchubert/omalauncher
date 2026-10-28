package dev.omalauncher.linux

/** Private, socket-only CUPS instance; never takes over the guest's system CUPS configuration. */
internal object GuestPrintingConfig {
    const val SERVER = "/usr/local/etc/omalauncher/printing"
    const val STATE = "/var/lib/omalauncher/cups"
    const val SPOOL = "/var/spool/omalauncher/cups"
    const val OUTPUT = "/var/spool/omalauncher-print"
    const val QUEUE = "Android"
    const val SOCKET = "${Proot.GUEST_RUNTIME_DIR}/omalauncher-cups.sock"
    const val PDF_CONFIG = "/etc/cups/cups-pdf-omalauncher.conf"
    const val BACKEND = "/usr/lib/cups/backend/omalauncher-pdf"
    const val FORWARDER = "/usr/local/lib/omalauncher/omalauncher-print-android"
    // pdftopdf must flatten copies/page selection into the document. The
    // package's PostScript path can leave NumCopies for a physical printer;
    // Ghostscript pdfwrite does not turn that into repeated PDF pages.
    const val FILTER = "/usr/lib/cups/filter/omalauncher-pdf"
    const val PDF_FILTER = "*cupsFilter2: \"application/pdf application/vnd.cups-postscript 0 omalauncher-pdf\""

    // libcupsfilters 2.2.1 reads copies from options, not CUPS argv[4]. Supply
    // both forms, retaining compatibility with older filter implementations.
    val filter = """
        #!/bin/sh
        case ${'$'}# in 5|6) ;; *) exit 1 ;; esac
        case "${'$'}4" in ''|*[!0-9]*) exit 1 ;; esac
        [ "${'$'}{#4}" -le 3 ] && [ "${'$'}4" -ge 1 ] && [ "${'$'}4" -le 999 ] || exit 1
        umask 077
        stage=${'$'}(mktemp -d "${'$'}{TMPDIR:-/tmp}/omalauncher-print.XXXXXXXX") || exit 1
        trap 'rm -f -- "${'$'}stage/document.pdf" "${'$'}stage/document.ps"; rmdir -- "${'$'}stage"' 0
        trap 'exit 1' HUP INT TERM
        if [ "${'$'}#" -eq 6 ]; then
            /usr/lib/cups/filter/pdftopdf "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5 copies=${'$'}4 emit-jcl=false" "${'$'}6" > "${'$'}stage/document.pdf" || exit 1
        else
            /usr/lib/cups/filter/pdftopdf "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4" "${'$'}5 copies=${'$'}4 emit-jcl=false" > "${'$'}stage/document.pdf" || exit 1
        fi
        /usr/bin/gs -q -dBATCH -dNOPAUSE -dSAFER -dNumCopies=1 -dASCII85EncodePages=true -sDEVICE=ps2write -sOutputFile="${'$'}stage/document.ps" "${'$'}stage/document.pdf" || exit 1
        cat -- "${'$'}stage/document.ps"
    """.trimIndent() + "\n"

    fun driver(original: String): String {
        return original.lineSequence().filterNot {
            val line = it.trimStart()
            line.startsWith("*cupsFilter:") || line.startsWith("*cupsFilter2:") || line.startsWith("*cupsManualCopies:")
        }.joinToString("\n").trimEnd() + "\n*cupsManualCopies: True\n" + PDF_FILTER + "\n"
    }

    // User/Group deliberately omitted: use this CUPS package's compiled accounts,
    // rather than assuming Debian's lp account exists on Arch. Authentication is
    // unnecessary on an app-private Unix socket and peer uid != proot's fake 0.
    val files = """
        SystemGroup root
        PeerCred Off
        ServerRoot $STATE
        StateDir $STATE
        RequestRoot $SPOOL
        CacheDir /var/cache/omalauncher/cups
        TempDir $SPOOL/tmp
        ServerBin /usr/lib/cups
        DataDir /usr/share/cups
        DocumentRoot /usr/share/cups/doc
        ErrorLog stderr
        AccessLog /dev/null
        PageLog /dev/null
        FileDevice No
    """.trimIndent() + "\n"

    val daemon = """
        Listen $SOCKET
        MaxCopies 999
        Browsing Off
        WebInterface No
        DefaultShared No
        DefaultAuthType None
        DefaultPolicy default
        ErrorPolicy abort-job
        LogLevel warn
        PreserveJobFiles No
        PreserveJobHistory Yes
        MaxJobs 100
        <Location />
          Order allow,deny
          Allow from all
        </Location>
        <Location /admin>
          AuthType None
          Order allow,deny
          Allow from all
        </Location>
        <Policy default>
          <Limit All>
            Order allow,deny
            Allow from all
          </Limit>
        </Policy>
    """.trimIndent() + "\n"

    val printer = """
        <DefaultPrinter $QUEUE>
        Info Android print services (CUPS-PDF)
        DeviceURI omalauncher-pdf:/
        State Idle
        Accepting Yes
        Shared No
        JobSheets none none
        OpPolicy default
        ErrorPolicy abort-job
        </DefaultPrinter>
    """.trimIndent() + "\n"

    val pdf = """
        Out $OUTPUT/output
        Spool $OUTPUT/tmp
        Log $OUTPUT/log
        Grp root
        AnonUser root
        AnonDirName $OUTPUT/output
        UserUMask 0077
        LogType 3
        Label 1
        AllowUnsafe 0
        PostProcessing $FORWARDER
    """.trimIndent() + "\n"
}
