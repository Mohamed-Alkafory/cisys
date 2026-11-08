<#macro content>
<footer class="cisys-footer" data-cisys-api="${properties.cisysApiUrl!'http://localhost:8080'}">
    Copyright &copy; ${.now?string('yyyy')} Powered by
    <a href="https://github.com/mnemosyne-systems/cisys" target="_blank" rel="noreferrer">cisys</a>
</footer>
</#macro>
