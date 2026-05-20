<%@ include file="/WEB-INF/template/include.jsp" %>
<openmrs:require privilege="Manage Global Properties"
                 otherwise="/login.htm"
                 redirect="/module/appointmentnotifier/settings.form"/>
<%@ include file="/WEB-INF/template/header.jsp" %>

<style>
  .an-page        { max-width: 760px; margin: 24px auto; font-family: sans-serif; }
  .an-page h2     { margin-bottom: 20px; font-size: 1.5em; }
  .an-section     { border: 1px solid #d0d0d0; border-radius: 4px; margin-bottom: 20px; }
  .an-section-hdr { background: #f4f4f4; padding: 10px 16px; font-weight: bold;
                    font-size: 0.95em; border-bottom: 1px solid #d0d0d0; border-radius: 4px 4px 0 0; }
  .an-section-hdr .an-badge { float: right; font-size: 0.78em; font-weight: normal;
                               background: #5b9bd5; color: #fff; padding: 2px 8px;
                               border-radius: 10px; margin-top: 1px; }
  .an-body        { padding: 16px; }
  .an-row         { display: flex; align-items: baseline; margin-bottom: 12px; }
  .an-row label   { flex: 0 0 220px; font-weight: normal; color: #444; font-size: 0.9em; }
  .an-row input[type=text],
  .an-row input[type=password],
  .an-row select  { flex: 1; padding: 5px 8px; border: 1px solid #bbb;
                    border-radius: 3px; font-size: 0.9em; }
  .an-row .an-hint{ flex: 1; font-size: 0.78em; color: #888; margin-top: 2px; }
  .an-provider-section { border-top: 1px dashed #ddd; margin-top: 12px; padding-top: 12px; }
  .an-provider-section h4 { margin: 0 0 12px; font-size: 0.9em; color: #555; }
  .an-alert-ok    { background: #dff0d8; border: 1px solid #b2dba1; color: #3c763d;
                    padding: 10px 14px; border-radius: 3px; margin-bottom: 16px; }
  .an-actions     { text-align: right; }
  .an-btn         { background: #5b9bd5; color: #fff; border: none; padding: 8px 24px;
                    border-radius: 3px; cursor: pointer; font-size: 0.95em; }
  .an-btn:hover   { background: #4a87c0; }
</style>

<div class="an-page">
  <h2>Appointment Notifier - Settings</h2>

  <c:if test="${saved}">
    <div class="an-alert-ok">Settings saved successfully.</div>
  </c:if>

  <form method="post" action="settings.form">

    <!-- ── General ─────────────────────────────────────────────────────── -->
    <div class="an-section">
      <div class="an-section-hdr">General</div>
      <div class="an-body">

        <div class="an-row">
          <label>SaaS Endpoint URL</label>
          <input type="text" name="saasEndpoint" value="${fn:escapeXml(saasEndpoint)}"/>
        </div>
        <div class="an-row">
          <label></label>
          <span class="an-hint">Webhook URL of the SaaS backend that receives appointment events.</span>
        </div>

        <div class="an-row">
          <label>SaaS Webhook Token</label>
          <input type="password" name="saasWebhookToken" value="${fn:escapeXml(saasWebhookToken)}"
                 autocomplete="new-password"/>
        </div>
        <div class="an-row">
          <label></label>
          <span class="an-hint">Sent as: Authorization: Bearer &lt;token&gt;. Leave blank if not required.</span>
        </div>

        <div class="an-row">
          <label>Module Enabled</label>
          <select name="enabled">
            <option value="true"  ${enabled == 'true'  ? 'selected' : ''}>Yes - send notifications</option>
            <option value="false" ${enabled == 'false' ? 'selected' : ''}>No - pause all notifications</option>
          </select>
        </div>

        <div class="an-row">
          <label>Max Retries</label>
          <input type="text" name="maxRetries" value="${fn:escapeXml(maxRetries)}" style="width:60px;flex:none"/>
        </div>
        <div class="an-row">
          <label></label>
          <span class="an-hint">Failed outbox entries are retried up to this many times before being abandoned.</span>
        </div>

        <div class="an-row">
          <label>Hospital / Facility Name</label>
          <input type="text" name="hospitalName" value="${fn:escapeXml(hospitalName)}"/>
        </div>
        <div class="an-row">
          <label></label>
          <span class="an-hint">Included in outgoing payloads. Falls back to the encounter location name when blank.</span>
        </div>

      </div>
    </div>

    <!-- ── OpenMRS Credentials ───────────────────────────────────────── -->
    <div class="an-section">
      <div class="an-section-hdr">OpenMRS Credentials
        <span class="an-badge">Internal REST</span>
      </div>
      <div class="an-body">

        <div class="an-row">
          <label>OpenMRS Base URL</label>
          <input type="text" name="openmrsBaseUrl" value="${fn:escapeXml(openmrsBaseUrl)}"/>
        </div>
        <div class="an-row">
          <label></label>
          <span class="an-hint">Used for internal REST calls. In Docker keep this as http://localhost:8080/openmrs.</span>
        </div>

        <div class="an-row">
          <label>Username</label>
          <input type="text" name="openmrsUsername" value="${fn:escapeXml(openmrsUsername)}"
                 autocomplete="off"/>
        </div>

        <div class="an-row">
          <label>Password</label>
          <input type="password" name="openmrsPassword" value="${fn:escapeXml(openmrsPassword)}"
                 autocomplete="new-password"/>
        </div>

      </div>
    </div>

    <!-- ── Messaging Provider ────────────────────────────────────────── -->
    <div class="an-section">
      <div class="an-section-hdr">Messaging Provider</div>
      <div class="an-body">

        <div class="an-row">
          <label>Provider</label>
          <select id="messagingProvider" name="messagingProvider" onchange="anUpdateProvider()">
            <option value="SwiftSend"  ${messagingProvider == 'SwiftSend'  ? 'selected' : ''}>SwiftSend</option>
            <option value="AsyncFlow"  ${messagingProvider == 'AsyncFlow'  ? 'selected' : ''}>AsyncFlow</option>
            <option value="LegacyLink" ${messagingProvider == 'LegacyLink' ? 'selected' : ''}>LegacyLink</option>
            <option value="SecurePost" ${messagingProvider == 'SecurePost' ? 'selected' : ''}>SecurePost</option>
          </select>
        </div>

        <!-- SwiftSend / AsyncFlow ─────────────────────────────────── -->
        <div id="an-section-apikey" class="an-provider-section">
          <h4>SwiftSend / AsyncFlow</h4>
          <div class="an-row">
            <label>API Key</label>
            <input type="password" name="messagingProviderToken"
                   value="${fn:escapeXml(messagingProviderToken)}"
                   autocomplete="new-password"/>
          </div>
          <div class="an-row">
            <label></label>
            <span class="an-hint">Sent as: X-Messaging-Provider-Token</span>
          </div>
        </div>

        <!-- LegacyLink ───────────────────────────────────────────── -->
        <div id="an-section-basic" class="an-provider-section">
          <h4>LegacyLink</h4>
          <div class="an-row">
            <label>Username</label>
            <input type="text" name="messagingProviderUsername"
                   value="${fn:escapeXml(messagingProviderUsername)}"
                   autocomplete="off"/>
          </div>
          <div class="an-row">
            <label></label>
            <span class="an-hint">Sent as: X-Messaging-Provider-Username</span>
          </div>
          <div class="an-row">
            <label>Password</label>
            <input type="password" name="messagingProviderPassword"
                   value="${fn:escapeXml(messagingProviderPassword)}"
                   autocomplete="new-password"/>
          </div>
          <div class="an-row">
            <label></label>
            <span class="an-hint">Sent as: X-Messaging-Provider-Password</span>
          </div>
        </div>

        <!-- SecurePost ───────────────────────────────────────────── -->
        <div id="an-section-jwt" class="an-provider-section">
          <h4>SecurePost  </h4>
          <div class="an-row">
            <label>Client ID</label>
            <input type="text" name="messagingProviderClientId"
                   value="${fn:escapeXml(messagingProviderClientId)}"
                   autocomplete="off"/>
          </div>
          <div class="an-row">
            <label></label>
            <span class="an-hint">Sent as: X-Messaging-Provider-Client-Id</span>
          </div>
          <div class="an-row">
            <label>Client Secret</label>
            <input type="password" name="messagingProviderClientSecret"
                   value="${fn:escapeXml(messagingProviderClientSecret)}"
                   autocomplete="new-password"/>
          </div>
          <div class="an-row">
            <label></label>
            <span class="an-hint">Sent as: X-Messaging-Provider-Client-Secret</span>
          </div>
        </div>

      </div>
    </div>

    <div class="an-actions">
      <button type="submit" class="an-btn">Save Settings</button>
    </div>

  </form>
</div>

<script>
  function anUpdateProvider() {
    var provider = document.getElementById('messagingProvider').value;
    var sections = document.querySelectorAll('.an-provider-section');
    for (var i = 0; i < sections.length; i++) {
      sections[i].style.display = 'none';
    }
    if (provider === 'SwiftSend' || provider === 'AsyncFlow') {
      document.getElementById('an-section-apikey').style.display = 'block';
    } else if (provider === 'LegacyLink') {
      document.getElementById('an-section-basic').style.display = 'block';
    } else if (provider === 'SecurePost') {
      document.getElementById('an-section-jwt').style.display = 'block';
    }
  }
  anUpdateProvider();
</script>

<%@ include file="/WEB-INF/template/footer.jsp" %>
