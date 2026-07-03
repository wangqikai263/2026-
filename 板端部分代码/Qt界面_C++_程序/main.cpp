#include "mainwindow.h"
#include "splashscreen.h"

#include <QApplication>
#include <QPropertyAnimation>
#include <QScreen>
#include <QTimer>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);

    SplashScreen *splash = new SplashScreen();
    MainWindow *mainWindow = new MainWindow();

    if (QScreen *screen = QApplication::primaryScreen()) {
        QRect area = screen->geometry();
        splash->move(area.center() - splash->rect().center());
        mainWindow->move(area.center() - mainWindow->rect().center());
    }

    splash->showFullScreen();
    mainWindow->setWindowOpacity(0.0);
    mainWindow->setWindowFlags(Qt::Window | Qt::FramelessWindowHint);
    mainWindow->showFullScreen();
    mainWindow->lower();
    splash->raise();

    QObject::connect(splash, &SplashScreen::finished, [=]() {
        splash->hide();
        splash->close();
        splash->deleteLater();
        mainWindow->raise();

        QPropertyAnimation *fadeIn = new QPropertyAnimation(mainWindow, "windowOpacity", mainWindow);
        fadeIn->setDuration(220);
        fadeIn->setStartValue(0.0);
        fadeIn->setEndValue(1.0);
        fadeIn->setEasingCurve(QEasingCurve::OutCubic);
        fadeIn->start();
        QTimer::singleShot(80, mainWindow, [mainWindow]() {
            mainWindow->playEntranceAnimation();
        });
    });

    return app.exec();
}
