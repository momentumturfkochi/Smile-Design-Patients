package com.smiledesign.patients

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smiledesign.patients.data.PatientDatabase
import com.smiledesign.patients.data.PatientRecord
import com.smiledesign.patients.util.ExcelHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = PatientDatabase.getInstance(this).patientDao()
        setContent {
            MaterialTheme {
                PatientScreen(dao = dao)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientScreen(dao: com.smiledesign.patients.data.PatientDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var editingRecord by remember { mutableStateOf<PatientRecord?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    val patientsFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) dao.getAllFlow() else dao.searchFlow(searchQuery.trim())
    }
    val patients by patientsFlow.collectAsState(initial = emptyList())

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    try {
                        val imported = ExcelHelper.importFile(context, uri)
                        if (imported.isNotEmpty()) dao.insertAll(imported)
                        imported.size
                    } catch (e: Exception) {
                        -1
                    }
                }
                val msg = if (count >= 0) "Imported $count patient records" else "Failed to read file. Use .xlsx or .csv"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ExcelHelper.exportCsv(context, uri, dao.getAllOnce())
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                val msg = if (ok) "Backup exported successfully" else "Export failed"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smile Design Patients", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/csv",
                            "text/comma-separated-values",
                            "*/*"
                        ))
                    }) {
                        Text("Import Excel")
                    }
                    TextButton(onClick = {
                        exportLauncher.launch("SmileDesign_Patients_Backup.csv")
                    }) {
                        Text("Export")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingRecord = PatientRecord()
                showEditor = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Patient")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("Search name, phone, OP, place, treatment...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            Text(
                text = "${patients.size} record(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )

            if (patients.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isBlank())
                            "No patient records yet.\nTap 'Import Excel' or '+' to add."
                        else "No matching records found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 84.dp)
                ) {
                    items(patients, key = { it.id }) { patient ->
                        PatientCard(
                            patient = patient,
                            onClick = {
                                editingRecord = patient
                                showEditor = true
                            },
                            onCall = {
                                if (patient.phone.isNotBlank()) {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${patient.phone}"))
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showEditor && editingRecord != null) {
        PatientEditorDialog(
            initial = editingRecord!!,
            onDismiss = { showEditor = false },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) { dao.upsert(updated) }
                showEditor = false
            },
            onDelete = if (editingRecord!!.id != 0L) {
                {
                    scope.launch(Dispatchers.IO) { dao.delete(editingRecord!!) }
                    showEditor = false
                }
            } else null
        )
    }
}

@Composable
private fun PatientCard(
    patient: PatientRecord,
    onClick: () -> Unit,
    onCall: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = patient.name.ifBlank { "Unnamed Patient" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (patient.opNumber.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "OP: ${patient.opNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val meta = listOfNotNull(
                    patient.phone.takeIf { it.isNotBlank() },
                    patient.place.takeIf { it.isNotBlank() },
                    listOf(patient.age, patient.sex).filter { it.isNotBlank() }
                        .joinToString("/").takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall)
                }

                if (patient.treatment.isNotBlank() || patient.visitDate.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = listOf(patient.visitDate, patient.treatment)
                            .filter { it.isNotBlank() }
                            .joinToString(" — "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (patient.phone.isNotBlank()) {
                IconButton(onClick = onCall) {
                    Icon(Icons.Default.Phone, contentDescription = "Call")
                }
            }
        }
    }
}

@Composable
private fun PatientEditorDialog(
    initial: PatientRecord,
    onDismiss: () -> Unit,
    onSave: (PatientRecord) -> Unit,
    onDelete: (() -> Unit)?
) {
    var op by remember { mutableStateOf(initial.opNumber) }
    var name by remember { mutableStateOf(initial.name) }
    var phone by remember { mutableStateOf(initial.phone) }
    var place by remember { mutableStateOf(initial.place) }
    var age by remember { mutableStateOf(initial.age) }
    var sex by remember { mutableStateOf(initial.sex) }
    var date by remember { mutableStateOf(initial.visitDate) }
    var treatment by remember { mutableStateOf(initial.treatment) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (initial.id == 0L) "Add Patient" else "Edit Patient")
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(op, { op = it }, label = { Text("OP Number") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(place, { place = it }, label = { Text("Place") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(age, { age = it }, label = { Text("Age") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(sex, { sex = it }, label = { Text("Sex") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true)
                OutlinedTextField(treatment, { treatment = it }, label = { Text("Treatment") }, minLines = 2)
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    initial.copy(
                        opNumber = op.trim(),
                        name = name.trim(),
                        phone = phone.trim(),
                        place = place.trim(),
                        age = age.trim(),
                        sex = sex.trim(),
                        visitDate = date.trim(),
                        treatment = treatment.trim()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
