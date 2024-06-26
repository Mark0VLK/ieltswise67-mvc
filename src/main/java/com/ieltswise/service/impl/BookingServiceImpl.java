package com.ieltswise.service.impl;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.ConferenceSolutionKey;
import com.google.api.services.calendar.model.CreateConferenceRequest;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import com.ieltswise.controller.request.RegularSessionDataRequest;
import com.ieltswise.controller.request.SessionDataRequest;
import com.ieltswise.controller.response.SessionDataResponse;
import com.ieltswise.entity.TutorInfo;
import com.ieltswise.entity.UserLessonData;
import com.ieltswise.exception.BookingSessionException;
import com.ieltswise.exception.EmailNotFoundException;
import com.ieltswise.exception.NoAvailableLessonsException;
import com.ieltswise.repository.TutorInfoRepository;
import com.ieltswise.repository.UserLessonDataRepository;
import com.ieltswise.service.BookingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Date;
import java.util.List;

import static com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.load;
import static com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport;
import static com.google.api.client.json.gson.GsonFactory.getDefaultInstance;
import static com.google.api.services.calendar.CalendarScopes.CALENDAR;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

@Slf4j
@Service
public class BookingServiceImpl implements BookingService {

    private static final JsonFactory JSON_FACTORY = getDefaultInstance();
    private static final List<String> SCOPES = singletonList(CALENDAR);
    private static final String TOKENS_DIRECTORY_PATH = "src/main/resources/tokens";
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";
    private final UserLessonDataRepository userLessonDataRepository;
    private final TutorInfoRepository tutorInfoRepository;

    @Autowired
    public BookingServiceImpl(UserLessonDataRepository userLessonDataRepository,
                              TutorInfoRepository tutorInfoRepository) {
        this.userLessonDataRepository = userLessonDataRepository;
        this.tutorInfoRepository = tutorInfoRepository;
    }

    @Override
    public int getNumberOfAvailableLessons(String email) throws EmailNotFoundException {
        UserLessonData userLessonData = userLessonDataRepository.findByEmail(email);
        if (userLessonData == null) {
            throw new EmailNotFoundException("Student", email);
        }
        return userLessonData.getAvailableLessons();
    }

    @Override
    public SessionDataResponse bookTrialSession(final SessionDataRequest sessionData) throws Exception {
        final String studentEmail = sessionData.getStudentEmail();
        if (isUsedTrialLessonByStudent(studentEmail)) {
            throw new BookingSessionException(String.format("Already used a trial lesson for email: %s", studentEmail));
        } else {
            final String studentName = sessionData.getStudentName();
            final String eventLink = bookSession(sessionData, studentName);
            saveStudentUsedTrialLesson(studentEmail, studentName);
            sessionData.setEventLink(eventLink);
            return prepareSessionDataResponse(sessionData);
        }
    }

    @Override
    public SessionDataResponse bookRegularSession(RegularSessionDataRequest regularSessionDataRequest)
            throws Exception {
        String studentEmail = regularSessionDataRequest.getStudentEmail();
        UserLessonData userLessonData = userLessonDataRepository.findByEmail(studentEmail);

        if (userLessonData == null)
            throw new EmailNotFoundException("Student", studentEmail);

        if (userLessonData.getAvailableLessons() < 1)
            throw new NoAvailableLessonsException(String.format("No available lessons have been found " +
                    "for a student with this email: %s", studentEmail));

        int newAvailableLessons = userLessonData.getAvailableLessons() - 1;
        userLessonData.setAvailableLessons(newAvailableLessons);
        userLessonData.setLastBookingDate(new Date());
        userLessonDataRepository.save(userLessonData);
        regularSessionDataRequest.setEventLink(bookSession(regularSessionDataRequest, userLessonData.getName()));
        return prepareSessionDataResponse(regularSessionDataRequest);
    }

    @Override
    public Boolean isTrialAvailable(final String studentEmail) {
        return !isUsedTrialLessonByStudent(studentEmail);
    }

    private String bookSession(final SessionDataRequest sessionData, final String studentName)
            throws Exception {
        log.info("Attempting to create event for student: " + studentName);
        final Event event = prepareAndSendEvent(sessionData, studentName);
        log.info("Event created successfully for student: " + studentName);
        return event.getHtmlLink();
    }

    private boolean isUsedTrialLessonByStudent(String studentEmail) {
        UserLessonData student = userLessonDataRepository.findByEmail(studentEmail);
        return student != null && student.getUsedTrial();
    }

    private void saveStudentUsedTrialLesson(final String email, final String studentName) {
        final UserLessonData student = userLessonDataRepository.findByEmail(email);
        if (student == null) {
            final UserLessonData newStudent = new UserLessonData();
            newStudent.setEmail(email);
            newStudent.setName(studentName);
            newStudent.setUsedTrial(true);
            newStudent.setLastBookingDate(new Date());
            userLessonDataRepository.save(newStudent);
        } else if (!student.getUsedTrial()) {
            student.setUsedTrial(true);
            student.setName(studentName);
            userLessonDataRepository.save(student);
        }
    }

    private Event prepareAndSendEvent(final SessionDataRequest sessionData, final String studentName)
            throws GeneralSecurityException, IOException, EmailNotFoundException {
        final Event event = buildEventWithMeet(sessionData, studentName);
        final NetHttpTransport httpTransport = newTrustedTransport();
        final Calendar calendar = buildCalendarService(httpTransport);

        return calendar.events()
                .insert("primary", event)
                .setConferenceDataVersion(1)
                .setSendUpdates("all")
                .execute();
    }

    private Event buildEventWithMeet(final SessionDataRequest sessionData, final String studentName)
            throws EmailNotFoundException {
        // TODO: Add the ability to book regular sessions by using ".setRecurrence"
        return new Event()
                .setSummary("English with IELTSWise67")
                .setLocation("Online")
                .setDescription(prepareEventDescription(sessionData.getRequestedService(), studentName))
                .setConferenceData(prepareConferenceData())
                .setStart(prepareEventTime(sessionData.getStartDate()))
                .setEnd(prepareEventTime(sessionData.getEndDate()))
                .setAttendees(prepareEventAttendees(sessionData))
                .setGuestsCanModify(true)
                .setReminders(prepareReminders());
    }

    private ConferenceData prepareConferenceData() {
        final ConferenceSolutionKey conferenceSKey = createConferenceKey();
        final CreateConferenceRequest conferenceReq = createConferenceRequest(conferenceSKey);
        return createConferenceData(conferenceReq);
    }

    private EventDateTime prepareEventTime(String eventTime) {
        final DateTime dateTime = new DateTime(eventTime);
        return new EventDateTime()
                .setDateTime(dateTime)
                .setTimeZone("Europe/London");
    }

    private List<EventAttendee> prepareEventAttendees(final SessionDataRequest sessionData)
            throws EmailNotFoundException {

        TutorInfo tutorInfo = tutorInfoRepository.findByEmail(sessionData.getTutorEmail())
                .orElseThrow(() -> new EmailNotFoundException("Tutor", sessionData.getTutorEmail()));
        final EventAttendee[] attendees = new EventAttendee[]{
                new EventAttendee().setEmail(sessionData.getStudentEmail()),
                new EventAttendee().setEmail(tutorInfo.getEmail()).setResource(true).setOrganizer(true)
        };
        return asList(attendees);
    }

    private Event.Reminders prepareReminders() {
        final EventReminder[] reminderOverrides = createReminderOverrides();
        return new Event.Reminders()
                .setUseDefault(false)
                .setOverrides(asList(reminderOverrides));
    }

    private ConferenceSolutionKey createConferenceKey() {
        final ConferenceSolutionKey conferenceSKey = new ConferenceSolutionKey();
        conferenceSKey.setType("hangoutsMeet");
        return conferenceSKey;
    }

    private CreateConferenceRequest createConferenceRequest(ConferenceSolutionKey conferenceSKey) {
        final CreateConferenceRequest createConferenceReq = new CreateConferenceRequest();
        createConferenceReq.setRequestId("RequestId");
        createConferenceReq.setConferenceSolutionKey(conferenceSKey);
        return createConferenceReq;
    }

    private ConferenceData createConferenceData(CreateConferenceRequest conferenceReq) {
        final ConferenceData conferenceData = new ConferenceData();
        conferenceData.setCreateRequest(conferenceReq);
        return conferenceData;
    }

    private EventReminder[] createReminderOverrides() {
        return new EventReminder[]{
                new EventReminder().setMethod("email").setMinutes(24 * 60),
                new EventReminder().setMethod("popup").setMinutes(10),
        };
    }

    private Calendar buildCalendarService(NetHttpTransport httpTransport) throws IOException {
        return new Calendar.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                .setApplicationName("IELTSWise Google API")
                .build();
    }

    private SessionDataResponse prepareSessionDataResponse(final SessionDataRequest sessionData) {
        return SessionDataResponse.builder()
                .studentEmail(sessionData.getStudentEmail())
                .sessionTime(sessionData.getStartDate())
                .eventLink(sessionData.getEventLink())
                .requestedService(sessionData.getRequestedService())
                .build();
    }

    private String prepareEventDescription(String requestedService, String studentName) {
        return """
                <b>Student Name</b>\s
                %s<br>
                <b>Requested Service</b>\s
                %s"""
                .formatted(studentName, requestedService);
    }

    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
            throws IOException {
        final InputStream in = BookingServiceImpl.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            String errorMessage = "Resource not found: " + CREDENTIALS_FILE_PATH;
            log.error(errorMessage);
            throw new FileNotFoundException(errorMessage);
        }
        final GoogleClientSecrets clientSecrets = load(JSON_FACTORY, new InputStreamReader(in));

        final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
        final LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
}
